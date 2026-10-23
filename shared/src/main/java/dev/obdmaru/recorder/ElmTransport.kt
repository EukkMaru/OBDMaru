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

class BluetoothElm(private val log: SessionLog, private val active: () -> Boolean) : ElmTransport {
    @Volatile private var socket: BluetoothSocket? = null
    private data class Chunk(val bytes: ByteArray? = null, val error: String? = null)
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
                    val n = s.inputStream.read(buffer)
                    if (n < 0) throw IOException("Bluetooth stream EOF")
                    if (n == 0) continue
                    val bytes = buffer.copyOf(n)
                    log.event("rx", "request_id" to request,
                        "base64" to Base64.encodeToString(bytes, Base64.NO_WRAP),
                        "ascii" to bytes.toString(Charsets.US_ASCII))
                    queue.put(Chunk(bytes))
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
        log.event("tx", "request_id" to id, "command" to command, "phase" to phase,
            "timeout_ms" to timeoutMs, "base64" to Base64.encodeToString(wire, Base64.NO_WRAP))
        try {
            val s = socket ?: throw IOException("Not connected")
            s.outputStream.write(wire)
            s.outputStream.flush()
            val out = StringBuilder()
            while (active()) {
                val remaining = timeoutMs - (SystemClock.elapsedRealtime() - start)
                if (remaining <= 0) {
                    log.event("request_end", "request_id" to id, "outcome" to "timeout", "raw" to out.toString())
                    // Reconnect before sending another request: late replies must never match the next command.
                    throw IOException("No ELM prompt within ${timeoutMs}ms for $command")
                }
                val chunk = queue.poll(minOf(remaining, 250), TimeUnit.MILLISECONDS) ?: continue
                chunk.error?.let { throw IOException(it) }
                out.append(chunk.bytes!!.toString(Charsets.US_ASCII))
                if (out.length > 262144) throw IOException("Response exceeds 256KiB")
                if ('>' in out) {
                    val raw = out.toString()
                    log.event("request_end", "request_id" to id, "outcome" to Protocol.outcome(raw),
                        "raw" to raw, "duration_ms" to SystemClock.elapsedRealtime() - start)
                    Thread.sleep(15)
                    return raw
                }
            }
            throw IOException("Capture stopped")
        } catch (e: Exception) {
            log.event("request_error", "request_id" to id, "error" to e.toString())
            throw e
        } finally { request = null }
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
