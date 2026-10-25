package dev.obdmaru.recorder

import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import org.junit.Assert.*
import org.junit.Test

/** Run in separate installed target apps to exercise real different-UID abstract socket binds. */
class ObdGuardDeviceTest {
 @Test fun crossAppLease(){
  val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
  when(InstrumentationRegistry.getArguments().getString("guard_role")){
   "hold"->ObdConnectionGuard.acquire(context).use{Log.i("OBDGuardTest","LEASE_HELD");Thread.sleep(12000)}
   "blocked"->{
    try{ObdConnectionGuard.acquire(context).use{};fail("Other app acquired active lease")}
    catch(e:ObdGuardException){assertTrue(e.message!!,e.message!!.startsWith("Magic is using OBD"))}
   }
   "legacy"->{
    try{ObdConnectionGuard.acquire(context).use{};fail("Old installed peer was ignored")}
    catch(e:ObdGuardException){assertTrue(e.message!!,e.message!!.startsWith("Update Watcher"))}
   }
   else->ObdConnectionGuard.acquire(context).use{assertNotNull(it)}
  }
 }
}
