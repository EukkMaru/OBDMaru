package dev.obdmaru.recorder

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuidedCaptureTest {
    private fun until(label:String, predicate:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+25000
        while(!predicate()) { if(SystemClock.elapsedRealtime()>end)throw AssertionError(label);Thread.sleep(100) }
    }
    @Test fun guidedModeRecordsPlanMetricsAndKeepsCapturingWhenPaused() {
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext
        listOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.POST_NOTIFICATIONS).forEach {
            inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} $it").close()
        }
        val activity=inst.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            ctx.startForegroundService(Intent(ctx,CaptureService::class.java).setAction("START").putExtra("demo",true).putExtra("mode","GUIDED"))
            until("new capture file") { CaptureService.running && CaptureService.currentFile?.isFile==true }
            val f=requireNotNull(CaptureService.currentFile)
            until("guided plan and cadence metrics") { f.readText().contains("poll_metrics") }
            ctx.startService(Intent(ctx,CaptureService::class.java).setAction("GUIDE_PAUSE"))
            until("guide pause event") { f.readText().contains("guide_pause") }
            val size=f.length()
            until("raw data continues while guide paused") { f.length()>size+1000 }
            // Exercise guide completion without driving: explicit passenger skips are logged.
            repeat(GuidePlan.steps.size+1) {
                if (!f.readText().contains("guide_complete")) {
                    val before=f.readLines().count { it.contains("guide_skipped") }
                    ctx.startService(Intent(ctx,CaptureService::class.java).setAction("GUIDE_SKIP"))
                    until("skip is processed") { f.readLines().count { it.contains("guide_skipped") }>before }
                }
            }
            until("guide completion recorded") { f.readText().contains("guide_complete") }
            assertTrue(CaptureService.running)
            val afterGuide=f.length()
            until("recording continues after final instruction") { f.length()>afterGuide+500 }
            ctx.startService(Intent(ctx,CaptureService::class.java).setAction("STOP"))
            until("stop saves footer") { !CaptureService.running && f.readText().contains("session_end") }
            val e=f.readLines().map { JSONObject(it) }
            assertTrue(e.first().getBoolean("demo"))
            assertEquals("2.0.0",e.first().getString("app_version"))
            assertTrue(e.any { it.optString("type")=="guide_plan" && it.getBoolean("prompt_is_not_action") })
            assertTrue(e.any { it.optString("type")=="poll_plan" && it.optString("mode")=="GUIDED" })
            assertFalse(e.any { it.optString("type")=="capture_error" })
            assertEquals("stopped",e.last().getString("reason"))
        } finally {
            ctx.startService(Intent(ctx,CaptureService::class.java).setAction("STOP"))
            inst.runOnMainSync { activity.finish() }
        }
    }
}
