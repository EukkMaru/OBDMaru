package dev.obdmaru.recorder

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import android.util.Base64
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

interface ElmTransport : Closeable {
    fun query(command: String, phase: String, timeoutMs: Long = 6000): String
}

class BluetoothElm(private val log: SessionLog, private val requestGapMs:Long=15, private val detailedTiming:Boolean=false, private val active: () -> Boolean) : ElmTransport {
    @Volatile private var socket: BluetoothSocket? = null
    private data class Chunk(val bytes: ByteArray? = null, val error: String? = null,
        val readNs:Long=0, val logNs:Long=0)
    private val queue = LinkedBlockingQueue<Chunk>()
    @Volatile private var request: Long? = null

    @Suppress("MissingPermission")
    fun connect(device: BluetoothDevice, insecure: Boolean) {
        val s = if (insecure) device.createInsecureRfcommSocketToServiceRecord(SPP)
            else device.createRfcommSocketToServiceRecord(SPP)
        socket = s
        log.event("connect_attempt", "insecure" to insecure, "uuid" to SPP.toString())
        val watchdog = Thread {
            try { Thread.sleep(15000); if (!s.isConnected) s.close() } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        try { s.connect() } finally { watchdog.interrupt() }
        log.event("connected")
        Thread {
            try {
                val buffer = ByteArray(2048)
                while (active()) {
                    val readStart = if(detailedTiming)SystemClock.elapsedRealtimeNanos() else 0
                    val n = s.inputStream.read(buffer)
                    val readEnd = if(detailedTiming)SystemClock.elapsedRealtimeNanos() else 0
                    if (n < 0) throw IOException("Bluetooth stream EOF")
                    if (n == 0) continue
                    val bytes = buffer.copyOf(n)
                    val readRequest = request
                    val logStart = if(detailedTiming)SystemClock.elapsedRealtimeNanos() else 0
                    log.event("rx", "request_id" to readRequest,
                        "base64" to Base64.encodeToString(bytes, Base64.NO_WRAP),
                        "ascii" to bytes.toString(Charsets.US_ASCII),
                        "read_start_ns" to readStart.takeIf{detailedTiming}, "read_end_ns" to readEnd.takeIf{detailedTiming})
                    val logNs = if(detailedTiming)SystemClock.elapsedRealtimeNanos()-logStart else 0
                    queue.put(Chunk(bytes,readNs=readEnd,logNs=logNs))
                }
            } catch (e: Exception) {
                queue.offer(Chunk(error = e.toString()))
            }
        }.apply { name = "elm-reader"; isDaemon = true; start() }
    }

    @Synchronized override fun query(command: String, phase: String, timeoutMs: Long): String {
        if (!active()) throw IOException("Capture stopped")
        // Discard from matching only: every chunk has already been preserved by the reader.
        while (queue.poll() != null) {}
        val id = ids.incrementAndGet()
        request = id
        val wire = (command + "\r").toByteArray(Charsets.US_ASCII)
        val start = SystemClock.elapsedRealtime()
        val callNs = if(detailedTiming)SystemClock.elapsedRealtimeNanos() else 0
        log.event("tx", "request_id" to id, "command" to command, "phase" to phase,
            "timeout_ms" to timeoutMs, "base64" to Base64.encodeToString(wire, Base64.NO_WRAP))
        try {
            val s = socket ?: throw IOException("Not connected")
            val writeStart = if(detailedTiming)SystemClock.elapsedRealtimeNanos() else 0
            s.outputStream.write(wire)
            s.outputStream.flush()
            val writeEnd = if(detailedTiming)SystemClock.elapsedRealtimeNanos() else 0
            val out = StringBuilder()
            var firstRead=0L;var lastRead=0L;var firstDequeue=0L;var lastDequeue=0L
            var chunks=0;var bytes=0;var rxLogNs=0L;var maxQueueNs=0L
            while (active()) {
                val remaining = timeoutMs - (SystemClock.elapsedRealtime() - start)
                if (remaining <= 0) {
                    log.event("request_end", "request_id" to id, "outcome" to "timeout", "raw" to out.toString())
                    // Reconnect before sending another request: late replies must never match the next command.
                    throw IOException("No ELM prompt within ${timeoutMs}ms for $command")
                }
                val chunk = queue.poll(minOf(remaining, 250), TimeUnit.MILLISECONDS) ?: continue
                val dequeue = if(detailedTiming)SystemClock.elapsedRealtimeNanos() else 0
                chunk.error?.let { throw IOException(it) }
                if(detailedTiming) {
                    if(chunks==0){firstRead=chunk.readNs;firstDequeue=dequeue}
                    chunks++;bytes+=chunk.bytes!!.size;lastRead=chunk.readNs;lastDequeue=dequeue
                    rxLogNs+=chunk.logNs;maxQueueNs=maxOf(maxQueueNs,dequeue-chunk.readNs)
                }
                out.append(chunk.bytes!!.toString(Charsets.US_ASCII))
                if (out.length > 262144) throw IOException("Response exceeds 256KiB")
                if ('>' in out) {
                    val raw = out.toString()
                    val endLogStart = if(detailedTiming)SystemClock.elapsedRealtimeNanos() else 0
                    log.event("request_end", "request_id" to id, "outcome" to Protocol.outcome(raw),
                        "raw" to raw, "duration_ms" to SystemClock.elapsedRealtime() - start)
                    val endLogEnd = if(detailedTiming)SystemClock.elapsedRealtimeNanos() else 0
                    if(requestGapMs>0)Thread.sleep(requestGapMs)
                    if(detailedTiming)log.event("request_timing", "request_id" to id,
                        "call_ns" to callNs, "write_start_ns" to writeStart, "write_end_ns" to writeEnd,
                        "first_read_ns" to firstRead,"last_read_ns" to lastRead,
                        "first_dequeue_ns" to firstDequeue,"last_dequeue_ns" to lastDequeue,
                        "chunk_count" to chunks,"rx_bytes" to bytes,"rx_log_ns" to rxLogNs,
                        "max_delivery_to_dequeue_ns" to maxQueueNs,
                        "tx_log_and_prepare_ns" to writeStart-callNs,
                        "request_end_log_ns" to endLogEnd-endLogStart,
                        "post_prompt_ns" to SystemClock.elapsedRealtimeNanos()-endLogEnd,
                        "timing_scope" to "phone RFCOMM stream delivery; not CAN or radio timestamps")
                    return raw
                }
            }
            throw IOException("Capture stopped")
        } catch (e: Exception) {
            log.event("request_error", "request_id" to id, "error" to e.toString())
            throw e
        } finally { request = null }
    }
    /** Abort a stuck read, drain through a quiet boundary, then require a fresh identity reply.
     * A late diagnostic prompt alone must never count as successful recovery.
     * Existing recorder/watcher behavior is unchanged unless they call this method.
     */
    @Synchronized fun recoverPrompt(): Boolean {
        if (!active()) return false
        return try {
            val s = socket ?: return false
            log.event("adapter_resync_start")
            val wire = "\r".toByteArray(Charsets.US_ASCII)
            log.event("adapter_resync_tx", "base64" to Base64.encodeToString(wire, Base64.NO_WRAP))
            s.outputStream.write(wire); s.outputStream.flush()
            val deadline = SystemClock.elapsedRealtime() + 1500
            var prompt = false
            var quiet = false
            while (active() && SystemClock.elapsedRealtime() < deadline) {
                val chunk = queue.poll(100, TimeUnit.MILLISECONDS)
                if (chunk == null) { if (prompt) { quiet = true; break }; continue }
                if (chunk.error != null) return false
                if (chunk.bytes?.toString(Charsets.US_ASCII)?.contains('>') == true) prompt = true
            }
            if (!prompt || !quiet || !active()) return false
            val identity = query("ATI", "adapter_resync", 2000).uppercase()
            val clean = listOf("ELM", "OBD", "STN").any { identity.contains(it) }
            log.event("adapter_resync_end", "success" to clean)
            clean
        } catch (e: Exception) {
            log.event("adapter_resync_error", "error" to e.toString()); false
        }
    }
    override fun close() { try { socket?.close() } catch (_: Exception) {} }
    companion object { private val ids = AtomicLong(); val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") }
}

class DemoElm(private val log: SessionLog) : ElmTransport {
    private var id = 0
    override fun query(command: String, phase: String, timeoutMs: Long): String {
        val req = ++id
        log.event("tx", "request_id" to req, "command" to command, "phase" to phase,
            "base64" to Base64.encodeToString((command + "\r").toByteArray(), Base64.NO_WRAP), "timeout_ms" to timeoutMs)
        Thread.sleep(35)
        val raw = when (command) {
            "ATZ", "ATI" -> "ELM327 DEMO\r>"
            "ATDPN" -> "A6\r>"
            "ATDP" -> "ISO 15765-4 (CAN 11/500)\r>"
            "ATRV" -> "13.8V\r>"
            "0100" -> "7E8 06 41 00 08 18 00 00\r>"
            "0105" -> "7E8 03 41 05 7B\r>"
            "010C" -> "7E8 04 41 0C 0C 80\r>"
            "010D" -> "7E8 03 41 0D 00\r>"
            "0149" -> "7E8 03 41 49 26\r>"
            "014A" -> "7E8 03 41 4A 27\r>"
            "2201A4" -> "7E9 03 7F 22 31\r>"
            else -> if (command.startsWith("AT")) "OK\r>" else "NO DATA\r>"
        }
        log.event("rx", "request_id" to req, "base64" to Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP), "ascii" to raw)
        log.event("request_end", "request_id" to req, "outcome" to Protocol.outcome(raw), "raw" to raw)
        return raw
    }
    override fun close() {}
}
