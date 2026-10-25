package dev.obdmaru.recorder

import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.system.ErrnoException
import android.system.OsConstants
import java.io.Closeable
import java.io.IOException

/** No shared storage, root, network listener, heartbeat or stale lock files. Kernel closes on death. */
object ObdConnectionGuard {
    private data class App(val pkg:String,val name:String,val version:Long,val release:String)
    private val apps=listOf(
        App("dev.obdmaru.recorder","Magic",4,"2.0.2"),
        App("dev.obdmaru.watcher","Watcher",2,"0.1.1"),
        App("dev.obdmaru.dashboard","Dash",4,"0.1.3"))
    fun acquire(context:Context):ConnectionLease {
        val mine=apps.first{it.pkg==context.packageName}
        // Old installed OBDMaru builds do not participate. Fail closed until all installed peers are updated.
        for(app in apps.filter{it.pkg!=mine.pkg}) {
            val info=try{context.packageManager.getPackageInfo(app.pkg,0)}catch(_:PackageManager.NameNotFoundException){continue}
            if(info.longVersionCode<app.version)throw ObdGuardException("Update ${app.name} to ${app.release} or newer before using OBD. Its installed version has no connection guard.")
        }
        return ConnectionLease.acquire(mine.name){name->
            try { val socket=LocalServerSocket(name);Closeable{socket.close()} }
            catch(e:IOException){
                val busy=generateSequence<Throwable>(e){it.cause}.any{it is ErrnoException && it.errno==OsConstants.EADDRINUSE} || e.message?.contains("EADDRINUSE")==true
                if(busy)throw LeaseNameBusy(e)else throw e
            }
        }
    }
}
