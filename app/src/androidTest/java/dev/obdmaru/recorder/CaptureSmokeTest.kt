package dev.obdmaru.recorder

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureSmokeTest {
    private fun until(message: String, timeout: Long = 25000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            if (SystemClock.elapsedRealtime() >= end) throw AssertionError(message)
            Thread.sleep(100)
        }
    }
    @Test fun demoRecordsFailuresStopsAndExportsExactBytes() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS)) {
            inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} $permission").close()
        }
        val activity = inst.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            ctx.startForegroundService(Intent(ctx, CaptureService::class.java).setAction("START").putExtra("demo", true))
            until("capture should start") { CaptureService.running && CaptureService.currentFile?.isFile == true }
            val file = requireNotNull(CaptureService.currentFile)
            until("TCM rejection should be captured") { file.readText().contains("7F 22 31") }
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction("MARK").putExtra("label", "smoke-test"))
            until("marker should be saved") { file.readText().contains("smoke-test") }
            // Moving the activity off screen must not stop the foreground capture.
            inst.runOnMainSync { activity.moveTaskToBack(true) }
            val size = file.length()
            until("capture should continue in background") { file.length() > size + 500 }
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction("STOP"))
            until("session should end cleanly") { !CaptureService.running && file.readText().contains("session_end") }
            val events = file.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
            assertTrue(events.first().getBoolean("demo"))
            assertEquals("session_end", events.last().getString("type"))
            assertTrue(events.any { it.optString("outcome") == "negative_response" })
            assertTrue(events.any { it.optString("outcome") == "no_data" })
            val tx = events.filter { it.getString("type") == "tx" }
            assertEquals(tx.size, tx.map { it.getLong("request_id") }.toSet().size)
            for (event in tx) {
                assertEquals(event.getString("command") + "\r", Base64.decode(event.getString("base64"), Base64.DEFAULT).toString(Charsets.US_ASCII))
            }
            val uri = Uri.Builder().scheme("content").authority("dev.obdmaru.recorder.logs").appendPath(file.name).build()
            val exported = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(file.readBytes(), exported)
            assertEquals("application/x-ndjson", ctx.contentResolver.getType(uri))
        } finally {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction("STOP"))
            inst.runOnMainSync { activity.finish() }
        }
    }
}
