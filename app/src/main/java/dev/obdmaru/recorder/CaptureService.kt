package dev.obdmaru.recorder

import android.app.*
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

class CaptureService : Service() {
    @Volatile private var active = false
    @Volatile private var transport: ElmTransport? = null
    @Volatile private var log: SessionLog? = null
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private var began = 0L
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> stopCapture()
            "MARK" -> log?.event("marker", "label" to intent.getStringExtra("label"))
            "START" -> if (!active) startCapture(intent)
        }
        return START_NOT_STICKY
    }
    private fun startCapture(intent: Intent) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("capture", "OBD capture", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "capture").setContentTitle("OBDMaru recording")
            .setContentText("Raw diagnostic recording in progress").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true).setContentIntent(open).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        active = true; running = true; began = SystemClock.elapsedRealtime()
        val demo = intent.getBooleanExtra("demo", false)
        val address = intent.getStringExtra("address") ?: "DEMO"
        val name = intent.getStringExtra("name") ?: "Demo"
        worker = thread(name = "obd-capture") {
            var reason = "stopped"
            try {
                wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OBDMaru:capture")
                    .apply { acquire(2 * 60 * 60 * 1000L) }
                val session = SessionLog(this, address, name, demo)
                log = session; currentFile = session.file
                // Store the exact plan with the data, so interpretation doesn't depend on a later app version.
                session.event("plan", "mode01" to "all support pages, then all PIDs 01..FF except support pages",
                    "tcm_header" to "7E1", "tcm_requests" to org.json.JSONArray(CapturePlan.TCM), "minimum_gap_ms" to 100)
                val probeJson = org.json.JSONArray(assets.open("extended-probes.json").bufferedReader().use { it.readText() })
                val probes = (0 until probeJson.length()).map { probeJson.getJSONObject(it).let { row -> row.getString("header") to row.getString("command") } }
                session.event("extended_plan", "probes" to probeJson)
                val link: ElmTransport = if (demo) DemoElm(session) else resilient(address, session)
                transport = link
                CapturePlan(link, session, { active && SystemClock.elapsedRealtime() - began < 2 * 60 * 60 * 1000 }, { status = it }, probes).run()
                if (active) reason = "two_hour_limit"
            } catch (e: Exception) {
                reason = if (active) "error: $e" else "stopped"
                try { log?.event("capture_error", "error" to e.toString(), "stack" to e.stackTraceToString()) } catch (_: Exception) {}
            } finally {
                active = false; running = false
                transport?.close(); transport = null
                try { log?.close(reason) } catch (_: Exception) {}
                log = null
                if (wake?.isHeld == true) wake?.release()
                status = "Finished: $reason"
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
    @Suppress("MissingPermission")
    private fun resilient(address: String, session: SessionLog): ElmTransport {
        val device = getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        return object : ElmTransport {
            @Volatile var current: BluetoothElm? = null
            var header: String? = null
            var initialized = false
            var insecure = false
            fun connect() {
                var last: Exception? = null
                for (attempt in 1..6) {
                    if (!active) throw IOException("Stopped")
                    val next = BluetoothElm(session) { active }
                    current = next
                    try {
                        status = "Connecting · attempt $attempt"
                        next.connect(device, insecure)
                        if (initialized) {
                            for (c in listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH1", "ATAT1", "ATST96", "ATSP0")) {
                                val r = next.query(c, "reconnect_setup", 12000)
                                if (c != "ATZ" && !r.contains("OK")) throw IOException("Setup rejected: $c: $r")
                            }
                            header?.let {
                                if (!next.query(it, "reconnect_header").contains("OK")) throw IOException("Header restore failed")
                            }
                        }
                        return
                    } catch (e: Exception) {
                        last = e
                        session.event("reconnect_error", "attempt" to attempt, "error" to e.toString())
                        next.close(); insecure = !insecure
                        for (i in 0 until attempt * 4) { if (!active) throw IOException("Stopped"); Thread.sleep(250) }
                    }
                }
                throw IOException("Unable to reconnect after six attempts", last)
            }
            override fun query(command: String, phase: String, timeoutMs: Long): String {
                if (current == null) connect()
                for (attempt in 0..1) {
                    try {
                        val raw = current!!.query(command, phase, timeoutMs)
                        if (command.startsWith("ATSH") && raw.contains("OK")) header = command
                        if (command == "ATSP0" && raw.contains("OK")) initialized = true
                        return raw
                    } catch (e: Exception) {
                        if (!active) throw e
                        session.event("link_recovery", "command" to command, "attempt" to attempt, "error" to e.toString())
                        current?.close(); current = null
                        if (attempt == 0) connect()
                    }
                }
                if (command.startsWith("AT")) throw IOException("Required adapter operation failed twice: $command")
                session.event("request_abandoned", "command" to command, "phase" to phase)
                // Advance discovery after a consistently failing ECU query, rather than retry forever.
                return ""
            }
            override fun close() { current?.close() }
        }
    }
    private fun stopCapture() {
        active = false
        status = "Stopping and saving…"
        transport?.close()
        if (worker == null) { running = false; stopSelf() }
    }
    override fun onDestroy() { stopCapture(); super.onDestroy() }
    companion object {
        @Volatile var running = false
        @Volatile var status = "Ready"
        @Volatile var currentFile: File? = null
    }
}
