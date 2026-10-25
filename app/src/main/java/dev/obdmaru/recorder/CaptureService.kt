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
    private var speaker: GuideSpeaker? = null
    private var guide: GuideRunner? = null
    @Volatile private var latest = LiveSample()
    @Volatile private var guidePaused = false
    @Volatile private var skipGuide = false
    private var guideCompletedLogged = false
    private var announcedWait: String? = null
    override fun onCreate() { super.onCreate(); speaker = GuideSpeaker(this) }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> stopCapture()
            "MARK" -> log?.event("marker", "label" to intent.getStringExtra("label"))
            "GUIDE_PAUSE" -> { guidePaused = !guidePaused; log?.event("guide_pause", "paused" to guidePaused) }
            "GUIDE_SKIP" -> { skipGuide = true }
            "GUIDE_REPEAT" -> guide?.current?.let { speaker?.say(it.words, it.id + "-repeat") }
            "START" -> if (!active && worker?.isAlive != true) startCapture(intent)
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
        active = true; running = true; currentFile = null; latest = LiveSample()
        guided = intent.getStringExtra("mode") == CaptureMode.GUIDED.name
        guide = if (guided) GuideRunner() else null
        guidePaused = false; skipGuide = false; guideCompletedLogged = false; announcedWait = null
        guideText = if (guided) "Wait while parked for connection checks and spoken guidance." else "Free drive · no instructions"
        began = SystemClock.elapsedRealtime()
        val demo = intent.getBooleanExtra("demo", false)
        val address = intent.getStringExtra("address") ?: "DEMO"
        val name = intent.getStringExtra("name") ?: "Demo"
        worker = thread(name = "obd-capture") {
            var reason = "stopped"
            var lease: ConnectionLease? = null
            try {
                if (!demo) lease = ObdConnectionGuard.acquire(this)
                wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OBDMaru:capture")
                    .apply { acquire(2 * 60 * 60 * 1000L) }
                val session = SessionLog(this, address, name, demo, "2.0.2")
                log = session; currentFile = session.file
                session.event("obd_connection_guard", "exclusive" to !demo, "demo" to demo)
                speaker?.log = session
                session.event("plan", "capture_mode" to if (guided) "GUIDED" else "MAGIC", "minimum_gap_ms" to 15,
                    "scope" to "prioritized fast signals plus finite read-only discovery", "speech_state" to speaker?.state)
                if (guided) session.event("guide_plan", "assumed_limit_kph" to 90, "prompt_is_not_action" to true, "guide_version" to GuidePlan.VERSION,
                    "timers_wait_for_speech" to true, "completion_words" to GuidePlan.COMPLETION, "parking_brake_observable" to false, "cruise_state_observable" to false,
                    "steps" to org.json.JSONArray(GuidePlan.steps.map { step -> org.json.JSONObject()
                        .put("id", step.id).put("duration_s",step.seconds).put("moving",step.moving)
                        .put("max_prompt_speed_kph",step.maxKph).put("words",step.words)
                        .put("expected_before",step.expectedBefore).put("expected_after",step.expectedAfter) }))
                val probeJson = org.json.JSONArray(assets.open("extended-probes.json").bufferedReader().use { it.readText() })
                val probes = (0 until probeJson.length()).map { probeJson.getJSONObject(it).let { row -> row.getString("header") to row.getString("command") } }
                session.event("extended_plan", "probes" to probeJson)
                val link: ElmTransport = if (demo) DemoElm(session) else resilient(address, session)
                transport = link
                CapturePlan(link, session, { active && SystemClock.elapsedRealtime() - began < 2 * 60 * 60 * 1000 },
                    { status = it }, probes, if (guided) CaptureMode.GUIDED else CaptureMode.MAGIC,
                    { latest = it }, { guideTick(session) }).run()
                if (active) reason = "two_hour_limit"
            } catch (e: Exception) {
                if (e is ObdGuardException) Handler(Looper.getMainLooper()).post { android.widget.Toast.makeText(this,e.message,android.widget.Toast.LENGTH_LONG).show() }
                reason = if (active) "error: $e" else "stopped"
                try { if (active) log?.event("capture_error", "error" to e.toString(), "stack" to e.stackTraceToString()) } catch (_: Exception) {}
            } finally {
                active = false
                transport?.close(); transport = null
                lease?.close(); lease = null
                try { log?.close(reason) } catch (_: Exception) {}
                speaker?.log = null
                log = null
                if (wake?.isHeld == true) wake?.release()
                status = "Finished: $reason"
                guided = false; running = false
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
    private fun guideTick(session: SessionLog) {
        val runner = guide ?: return
        if (!active) return
        if (skipGuide) {
            session.event("guide_skipped", "stage" to runner.current?.id, "reason" to "user requested; no action assumed")
            runner.advance(SystemClock.elapsedRealtime()); skipGuide = false
        }
        if (runner.done) {
            if (!guideCompletedLogged) {
                session.event("guide_complete", "capture_continues" to true)
                speaker?.say(GuidePlan.COMPLETION, "complete")
                guideCompletedLogged = true
            }
            guideText = "Guide complete · recording continues until you stop while parked."
            return
        }
        if (speaker?.ready != true) {
            guideText = "${speaker?.state}. Guided progression waits; raw capture continues."
            return
        }
        val time = SystemClock.elapsedRealtime()
        val speed = if (!guidePaused && time-latest.speedAtMs < 5000) latest.speedKph else null
        val step = runner.tick(time,speed,countTime = speaker?.speaking != true)
        runner.takeAutomaticSkip()?.let {
            session.event("guide_skipped", "stage" to it, "reason" to "No suitable lower-speed window within 75 seconds")
            speaker?.say("We are skipping the optional stronger input window. Continue normally.","automatic_skip")
        }
        val waiting = runner.current
        if (!guidePaused && speaker?.speaking != true && runner.paused && waiting?.maxKph != null && speed != null && speed > waiting.maxKph && announcedWait != waiting.id) {
            speaker?.say("The next optional pedal window waits for a naturally lower pace. Keep driving normally; do not slow down just for this test. It will be skipped if no suitable opportunity appears.","waiting_"+waiting.id)
            session.event("guide_wait", "stage" to waiting.id, "reason" to "speed headroom", "speed_kph" to speed)
            announcedWait = waiting.id
        }
        if (step != null) {
            session.event("guide_step", "index" to runner.index, "stage" to step.id, "words" to step.words,
                "intended_window_s" to step.seconds, "driver_action_confirmed" to false,
                "expected_before" to step.expectedBefore, "expected_after" to step.expectedAfter,
                "speed_kph" to latest.speedKph)
            speaker?.say(step.words,step.id)
        }
        val current = runner.current
        guideText = if (guidePaused) "Guide paused · raw recording continues" else if (current == null) "Finishing guide…" else
            "${runner.index+1}/${GuidePlan.steps.size} · ${current.id.replace('_',' ')} · ${runner.remainingSeconds()}s" +
            if (runner.paused) if (speaker?.speaking == true) " · speaking; observation timer waits" else " · waiting for suitable movement/speed data" else "\n${current.words}"
    }
    private fun stopCapture() {
        if (!active) return
        active = false
        status = "Stopping and saving…"
        transport?.close()
        if (worker == null) { running = false; stopSelf() }
    }
    override fun onDestroy() { stopCapture(); speaker?.close(); super.onDestroy() }
    companion object {
        @Volatile var running = false
        @Volatile var status = "Ready"
        @Volatile var guided = false
        @Volatile var guideText = ""
        @Volatile var currentFile: File? = null
    }
}
