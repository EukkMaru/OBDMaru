package dev.obdmaru.dashboard

import java.io.Closeable
import java.io.IOException
import dev.obdmaru.recorder.Protocol

interface DashboardLink : Closeable {
    fun query(command: String, phase: String, timeoutMs: Long): String
    /** Establish a clean command boundary, not just receipt of any late prompt. */
    fun recover(): Boolean
}

/** One serial link; deadlines never queue catch-up bursts. Independent channel backoff. */
class DashboardPolling(
    private val open: () -> DashboardLink,
    private val now: () -> Long,
    private val sleep: (Long) -> Unit,
    private val publish: (DashState) -> Unit,
    private val event: (String, Map<String,Any?>) -> Unit = { _,_ -> },
    private val demo: Boolean = false
) : Closeable {
    private data class Task(val key: String,val header: String,val command: String,val interval: Long,val size: Int,var due: Long=0,var failures: Int=0)
    private val tasks = listOf(Task("tcm","7E1","2201A4",100,34), Task("brake","7D1","22C101",100,34), Task("speed","7DF","010D",333,1))
    private val samples = mutableMapOf<String,Sample>()
    private val times = mutableMapOf<String,ArrayDeque<Long>>()
    private var link: DashboardLink? = null
    private var header = ""
    private var nextConnect = 0L
    private var firstConnection = true
    private var message = "Live"
    private var messageUntil = 0L
    private var stage = "connect"
    private fun status(text: String) { message=text;messageUntil=now()+5000 }
    private fun emit() = publish(DashState(samples.toMap(),if(now()<messageUntil)message else "Live",demo))
    private fun sample(key: String,bytes: ByteArray,at: Long) {
        val window=times.getOrPut(key){ArrayDeque()}
        window.addLast(at);while(window.size>1 && at-window.first()>3000)window.removeFirst()
        val hz=if(window.size>1)(window.size-1)*1000.0/(at-window.first()).coerceAtLeast(1)else 0.0
        samples[key]=Sample(bytes,at,hz)
    }
    fun step() {
        if(now()<nextConnect){sleep(minOf(50,nextConnect-now()));return}
        if(link==null) {
            try {
                stage="connect";status("Connecting…");emit();link=open()
                // Do not reboot the adapter on every reconnect. Reapply all required settings.
                val setup=listOf(if(firstConnection)"ATZ" else "ATPC","ATE0","ATL0","ATS1","ATH1","ATAT1","ATST32","ATSP6")
                for(cmd in setup) {
                    stage=cmd
                    val reply=link!!.query(cmd,"dashboard_setup",6000)
                    if(cmd!="ATZ" && cmd!="ATPC" && !reply.contains("OK"))throw IOException("Setup rejected: $cmd (${Protocol.outcome(reply)})")
                }
                firstConnection=false;header="";times.clear();status("Connected");event("dashboard_connected",emptyMap())
            }catch(e: IOException){reconnect(e);return}
        }
        val start=now();val task=tasks.maxBy{start-it.due}
        if(task.due>start){sleep(minOf(task.due-start,25));return}
        try {
            if(header!=task.header) {
                stage="${task.key} header"
                val reply=link!!.query("ATSH${task.header}","dashboard_setup",2500)
                if(!reply.contains("OK"))throw IOException("Header rejected: ${task.header}")
                header=task.header
            }
            stage="${task.key} ${task.command}"
            val raw=link!!.query(task.command,"dashboard_${task.key}",2500)
            val bytes=Protocol.data(raw,task.command)
            if(bytes==null || bytes.size<task.size) {
                fail(task,"${Protocol.outcome(raw)} / short or unmatched payload")
            }else{
                val at=now();sample(task.key,bytes,at);task.failures=0
                // Temperatures arrive with throttle/gear. Publish them at 1 Hz without another ECU read.
                if(task.key=="tcm" && (samples["clutch"]==null || at-samples.getValue("clutch").at>=1000))sample("clutch",bytes,at)
                task.due=maxOf(start+task.interval,at)
            }
            emit()
        }catch(e: IOException){
            fail(task,e.message?:"I/O error");emit()
            event("dashboard_recovery_attempt",mapOf("stage" to stage,"error" to e.toString()))
            // A timed-out ECU is not necessarily a disconnected Bluetooth socket.
            if(try{link?.recover()==true}catch(_:IOException){false}){header="";status("Adapter resynced · ${task.key} backed off");event("dashboard_resynced",mapOf("channel" to task.key));emit()}
            else reconnect(e)
        }
    }
    private fun fail(task: Task,reason: String) {
        task.failures++;samples.remove(task.key);times.remove(task.key)
        if(task.key=="tcm"){samples.remove("clutch");times.remove("clutch")}
        task.due=now()+minOf(5000,1000L*task.failures)
        status("${task.key}: $reason");event("dashboard_channel_failure",mapOf("channel" to task.key,"stage" to stage,"reason" to reason,"retry_ms" to task.due-now()))
    }
    private fun reconnect(e: IOException) {
        try{link?.close()}catch(_:Exception){};link=null;header=""
        nextConnect=now()+2000
        status("Retry in 2s · $stage: ${e.message}")
        // Retain original timestamps so surviving readings age out honestly; don't reset the whole UI.
        event("dashboard_reconnect",mapOf("stage" to stage,"error" to e.toString()))
        emit()
    }
    override fun close(){try{link?.close()}finally{link=null}}
}
