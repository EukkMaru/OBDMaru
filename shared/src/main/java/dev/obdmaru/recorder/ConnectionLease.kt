package dev.obdmaru.recorder

import java.io.Closeable
import java.io.IOException

class ObdGuardException(message:String,cause:Throwable?=null):IOException(message,cause)
class LeaseNameBusy(cause:Throwable?=null):IOException("Lease name already bound",cause)

/** Cross-process exclusion via an atomic OS bind, held until the service closes its Bluetooth socket.
 * The owner marker is only a label; the single global bind is the actual exclusion primitive.
 */
class ConnectionLease private constructor(private val marker:Closeable,private val lock:Closeable):Closeable {
    private var closed=false
    @Synchronized override fun close(){if(closed)return;closed=true;try{marker.close()}finally{lock.close()}}
    companion object {
        const val NAME="dev.obdmaru.obd.connection.v1"
        val owners=listOf("Magic","Watcher","Dash","Scanner")
        private fun marker(owner:String)="$NAME.owner.$owner"
        fun acquire(owner:String,bind:(String)->Closeable):ConnectionLease {
            require(owner in owners)
            val label=try{bind(marker(owner))}catch(e:LeaseNameBusy){throw ObdGuardException("$owner is already using OBD. Stop its recording first.",e)}
                catch(e:IOException){throw ObdGuardException("OBD connection guard unavailable; connection blocked.",e)}
            val lock=try{bind(NAME)}catch(e:IOException){
                label.close()
                if(e !is LeaseNameBusy)throw ObdGuardException("OBD connection guard unavailable; connection blocked.",e)
                val held=owners.filter { candidate->
                    try{bind(marker(candidate)).use{};false}catch(_:LeaseNameBusy){true}catch(_:IOException){false}
                }
                val who=held.singleOrNull()?:"Another OBDMaru app"
                throw ObdGuardException("$who is using OBD. Stop it there before starting $owner.",e)
            }
            return ConnectionLease(label,lock)
        }
    }
}
