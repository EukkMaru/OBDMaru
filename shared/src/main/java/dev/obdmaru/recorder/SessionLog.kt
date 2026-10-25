package dev.obdmaru.recorder

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SessionLog(context: Context, address: String, name: String, demo: Boolean, appVersion: String = "2.0.0", private val detailedTiming:Boolean=false) {
    val file: File
    private val stream: FileOutputStream
    private val buffered: BufferedOutputStream
    private val syncer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var writeFailure: Exception? = null
    private val start = SystemClock.elapsedRealtimeNanos()
    private var seq = 0L
    private var closed = false
    init {
        val dir = File(context.filesDir, "sessions").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        file = File(dir, "$stamp-${UUID.randomUUID().toString().take(8)}.jsonl")
        stream = FileOutputStream(file, true)
        buffered = BufferedOutputStream(stream, 32768)
        event("session_start", "schema" to 1, "app_version" to appVersion, "adapter_address" to address,
            "adapter_name" to name, "demo" to demo, "phone" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.RELEASE, "sdk" to Build.VERSION.SDK_INT,
            "vehicle" to "Hyundai Tucson TL 2018 pre-facelift 1.7D dry DCT", "durability_interval_ms" to 500)
        syncer.scheduleWithFixedDelay({
            try { sync() } catch (e: Exception) { writeFailure = e }
        }, 500, 500, TimeUnit.MILLISECONDS)
    }
    @Synchronized fun event(type: String, vararg fields: Pair<String, Any?>) {
        if (closed) return
        writeFailure?.let { throw java.io.IOException("Recording storage failed", it) }
        val obj = JSONObject().put("seq", ++seq).put("wall_ms", System.currentTimeMillis())
            .put("mono_ns", SystemClock.elapsedRealtimeNanos()).put("elapsed_ns", SystemClock.elapsedRealtimeNanos() - start)
            .put("type", type)
        fields.forEach { (k,v) -> obj.put(k, v ?: JSONObject.NULL) }
        buffered.write((obj.toString() + "\n").toByteArray(Charsets.UTF_8))
        if (type in setOf("session_start", "marker", "guide_step", "session_end")) sync()
    }
    @Synchronized private fun sync() {
        if (!closed) {
            val before=if(detailedTiming)SystemClock.elapsedRealtimeNanos() else 0
            buffered.flush(); stream.fd.sync()
            if(detailedTiming)event("log_sync", "sync_start_ns" to before,
                "sync_end_ns" to SystemClock.elapsedRealtimeNanos())
        }
    }
    @Synchronized fun close(reason: String) {
        if (closed) return
        try { event("session_end", "reason" to reason) }
        finally { closed = true; syncer.shutdownNow(); buffered.close() }
    }
}
