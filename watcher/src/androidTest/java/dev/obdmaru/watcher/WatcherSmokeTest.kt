package dev.obdmaru.watcher
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
class WatcherSmokeTest {
    @Test fun demoSwitchesBlocksMeasuresRateAndExportsRawLog() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        WatcherService.selected = WatchCatalog.blocks[18]; WatcherService.targetHz = 10
        ctx.startForegroundService(Intent(ctx,WatcherService::class.java).putExtra("demo",true))
        try {
            await { WatcherService.snapshot.rate > 7 && WatcherService.snapshot.values?.size == 34 }
            val s = WatcherService.snapshot
            assertTrue(s.demo); assertEquals(0,s.values!![0].toInt())
            assertTrue(WatchModel.unsigned(s.values).any { it > 1 })
            WatcherService.selected=WatchCatalog.blocks[8]; WatcherService.targetHz=5
            await { WatcherService.snapshot.block.index=="U09" && WatcherService.snapshot.rate in 3.5..6.0 && WatcherService.snapshot.values?.size==64 }
        } finally { ctx.startService(Intent(ctx,WatcherService::class.java).setAction("stop")); await { !WatcherService.running && WatcherService.lastFile?.readText()?.contains("session_end") == true } }
        val file = WatcherService.lastFile!!
        val text = ctx.contentResolver.openInputStream(Uri.parse("content://dev.obdmaru.watcher.logs/${file.name}"))!!.bufferedReader().use { it.readText() }
        assertTrue(text.contains("watcher-0.1.0")); assertTrue(text.contains("watcher_demo_sample")); assertTrue(text.contains("session_end")); assertTrue(text.contains("U09"))
    }
    private fun await(test:()->Boolean) { val end=SystemClock.elapsedRealtime()+12000; while(!test() && SystemClock.elapsedRealtime()<end) Thread.sleep(50); assertTrue("Timed out",test()) }
}
