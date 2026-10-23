package dev.obdmaru.watcher

import android.app.*
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.*
import dev.obdmaru.recorder.*
import java.io.File
import kotlin.math.sin

class WatcherService : Service() {
    private var worker: Thread? = null
    @Volatile private var transport: BluetoothElm? = null
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { running = false; transport?.close(); worker?.interrupt(); return START_NOT_STICKY }
        if (running || worker?.isAlive == true) return START_NOT_STICKY
        val demo = intent?.getBooleanExtra("demo", false) ?: false
        val address = intent?.getStringExtra("address") ?: ""
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("watch", "Live watcher", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WatcherService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(3, Notification.Builder(this, "watch").setContentTitle("OBDMaru Watcher")
            .setContentText("Raw byte watcher · tap to open").setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build())
        running = true; snapshot = Snapshot(status = "Connecting…", demo = demo)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OBDMaru:watcher").apply { acquire(2 * 60 * 60 * 1000L) }
        worker = Thread { capture(address, demo) }.apply { name = "watcher-poll"; start() }
        return START_NOT_STICKY
    }
    @Suppress("MissingPermission")
    private fun capture(address: String, demo: Boolean) {
        var log: SessionLog? = null
        try {
            log = SessionLog(this, address, "Watcher", demo, "watcher-0.1.0"); lastFile = log.file
            log.event("watcher_start", "index_basis" to "zero-based data bytes, service and identifier excluded", "target_hz" to targetHz)
            var current: Block? = null
            var currentHz = 0
            var headerDirty = true
            var nextConnect = 0L
            var attempt = 0
            var frame = 0
            val times = ArrayDeque<Long>()
            while (running) {
                val block = selected
                val hz = targetHz
                val start = SystemClock.elapsedRealtime()
                if (current != block || currentHz != hz) {
                    current = block; currentHz = hz; times.clear()
                    snapshot = Snapshot(block = block, status = "Waiting for response…", demo = demo)
                    log.event("watcher_selection", "index" to block.index, "header" to block.header, "command" to block.command, "target_hz" to hz)
                    // A header change is applied inside the recoverable query path below.
                    headerDirty = true
                }
                try {
                    if (!demo && transport == null) {
                        if (start < nextConnect) { Thread.sleep(100); continue }
                        snapshot = Snapshot(block = block, status = "Connecting (attempt ${attempt + 1})…")
                        val elm = BluetoothElm(log) { running }; transport = elm
                        val device = getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
                        elm.connect(device, attempt++ % 2 == 1)
                        for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH1", "ATAT1", "ATST32", "ATSP6", "ATSH${block.header}")) {
                            val reply = elm.query(cmd, "watcher_setup")
                            if (cmd != "ATZ" && !reply.contains("OK")) throw java.io.IOException("Setup rejected: $cmd")
                        }
                        log.event("watcher_protocol", "protocol" to "ISO 15765-4 CAN 11bit 500k, matching prior capture")
                        times.clear()
                    }
                    if (!demo && headerDirty) {
                        val reply = transport!!.query("ATSH${block.header}", "watcher_setup")
                        if (!reply.contains("OK")) throw java.io.IOException("Header rejected")
                        headerDirty = false
                    }
                    val bytes = if (demo) {
                        Thread.sleep(35)
                        ByteArray(block.length) { i -> when (i % 6) {
                            0 -> 0; 1 -> ((frame / 8) % 2).toByte(); 2 -> (128 + 110 * sin(frame * .08 + i)).toInt().toByte()
                            else -> ((frame * (i + 1) / 3) % 256).toByte()
                        } }.also { frame++; log.event("watcher_demo_sample", "index" to block.index, "bytes" to org.json.JSONArray(WatchModel.unsigned(it))) }
                    } else {
                        val raw = transport!!.query(block.command, "watcher_${block.index}", 2500)
                        Protocol.data(raw, block.command) ?: throw RejectedResponse(Protocol.outcome(raw))
                    }
                    val end = SystemClock.elapsedRealtime()
                    times.addLast(end); while (times.size > 1 && end - times.first() > 3000) times.removeFirst()
                    val rate = if (times.size > 1) (times.size - 1) * 1000.0 / (end - times.first()).coerceAtLeast(1) else 0.0
                    snapshot = Snapshot(block, bytes, end, rate, end - start, "Live", demo)
                } catch (e: RejectedResponse) {
                    times.clear(); snapshot = Snapshot(block = block, status = "No sample: ${e.message}", demo = demo)
                    log.event("watcher_no_sample", "index" to block.index, "reason" to e.message)
                    Thread.sleep(1000) // Do not hammer a module that rejects a read.
                } catch (e: Exception) {
                    if (!running) break
                    times.clear(); snapshot = Snapshot(block = block, status = "Connection error · retry in 2s", demo = demo)
                    log.event("watcher_error", "error" to e.toString())
                    transport?.close(); transport = null; headerDirty = true; nextConnect = SystemClock.elapsedRealtime() + 2000
                }
                Thread.sleep(WatchModel.delayMs(start, SystemClock.elapsedRealtime(), hz))
            }
        } catch (e: Exception) {
            if (running) snapshot = snapshot.copy(values = null, status = "Stopped: ${e.message}")
        } finally {
            transport?.close(); transport = null
            try { log?.close("watcher stopped") } catch (_: Exception) {}
            running = false; snapshot = snapshot.copy(values = null, status = if (snapshot.status.startsWith("Stopped:")) snapshot.status else "Stopped · log saved")
            stopSelf()
        }
    }
    override fun onDestroy() {
        running = false; transport?.close(); worker?.interrupt()
        if (wake?.isHeld == true) wake?.release()
        super.onDestroy()
    }
    private class RejectedResponse(message: String) : Exception(message)
    companion object {
        @Volatile var running = false
        @Volatile var selected = WatchCatalog.blocks[18]
        @Volatile var targetHz = 5
        @Volatile var lastFile: File? = null
        @Volatile var snapshot = Snapshot()
    }
}
data class Snapshot(val block: Block = WatcherService.selected, val values: ByteArray? = null, val at: Long = 0, val rate: Double = 0.0, val latency: Long = 0, val status: String = "Ready", val demo: Boolean = false)
