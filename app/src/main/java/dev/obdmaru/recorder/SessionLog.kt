package dev.obdmaru.recorder

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SessionLog(context: Context, address: String, name: String, demo: Boolean) {
    val file: File
    private val stream: FileOutputStream
    private val start = SystemClock.elapsedRealtimeNanos()
    private var seq = 0L
    private var closed = false
    init {
        val dir = File(context.filesDir, "sessions").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        file = File(dir, "$stamp-${UUID.randomUUID().toString().take(8)}.jsonl")
        stream = FileOutputStream(file, true)
        event("session_start", "schema" to 1, "app_version" to "0.1.0", "adapter_address" to address,
            "adapter_name" to name, "demo" to demo, "phone" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.RELEASE, "sdk" to Build.VERSION.SDK_INT,
            "vehicle" to "Hyundai Tucson TL 2018 pre-facelift 1.7D dry DCT")
    }
    @Synchronized fun event(type: String, vararg fields: Pair<String, Any?>) {
        if (closed) return
        val obj = JSONObject().put("seq", ++seq).put("wall_ms", System.currentTimeMillis())
            .put("mono_ns", SystemClock.elapsedRealtimeNanos()).put("elapsed_ns", SystemClock.elapsedRealtimeNanos() - start)
            .put("type", type)
        fields.forEach { (k,v) -> obj.put(k, v ?: JSONObject.NULL) }
        stream.write((obj.toString() + "\n").toByteArray(Charsets.UTF_8))
        stream.flush()
        // Persist each event; a killed process still leaves an analysable JSONL prefix.
        stream.fd.sync()
    }
    @Synchronized fun close(reason: String) {
        if (closed) return
        event("session_end", "reason" to reason)
        closed = true
        stream.close()
    }
}
