package dev.obdmaru.dashboard
import android.content.Intent
import android.os.SystemClock
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
class DashboardSmokeTest {
 @Test fun demoUpdatesAllChannelsAndExportsCompletedLog(){
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.BLUETOOTH_CONNECT)
  ctx.startForegroundService(Intent(ctx,DashboardService::class.java).putExtra("demo",true))
  try {
   await{DashboardService.state.samples.size==6 && DashboardService.state.samples["tcm"]!!.hz>7 && DashboardService.state.samples["speed"]!!.hz>2 && DashboardService.state.samples["clutch"]!!.hz>0.5}
   val state=DashboardService.state;assertTrue(state.demo)
   val t=Telemetry.transmission(state.samples["tcm"]!!.bytes)
   assertNotNull(t.clutch1);assertNotNull(t.clutch2);assertNotNull(t.gas)
   assertNotNull(Telemetry.rpm(state.samples["rpm"]!!.bytes))
   assertNotNull(Telemetry.indicators(state.samples["indicators"]!!.bytes))
   assertNotNull(Telemetry.brake(state.samples["brake"]!!.bytes).percent)
   assertTrue(Telemetry.u(state.samples["speed"]!!.bytes,0)!! in 0..100)
  }finally{ctx.startService(Intent(ctx,DashboardService::class.java).setAction("stop"));await{!DashboardService.running && DashboardService.lastFile?.readText()?.contains("session_end")==true}}
  assertTrue(DashboardService.state.samples.isEmpty())
  val file=DashboardService.lastFile!!
  val log=ctx.contentResolver.openInputStream(Uri.parse("content://dev.obdmaru.dashboard.logs/${file.name}"))!!.bufferedReader().use{it.readText()}
  assertTrue(log.contains("dashboard_demo_sample"));assertTrue(log.contains("dashboard-0.1.6"))
 }
 private fun await(test:()->Boolean){val end=SystemClock.elapsedRealtime()+12000;while(!test()&&SystemClock.elapsedRealtime()<end)Thread.sleep(50);assertTrue("Timed out",test())}
}
