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
    private data class Task(val key: String,var header: String,val command: String,val interval: Long,val size: Int,var due: Long=0,var failures: Int=0,val hint: ReplyHint=ReplyHint(),val optional:Boolean=false,
        var costMs:Double=25.0,var queryMs:Double=25.0,var lastStarted:Long?=null)
    private val engineChannels = setOf("speed","rpm","coolant")
    private val tasks = listOf(Task("tcm","7E1","2201A4",100,34), Task("brake","7D1","22C101",100,34), Task("speed","7E0","010D",333,1), Task("rpm","7E0","010C",200,2), Task("coolant","7E0","0105",1000,1), Task("indicators","770","22BC08",500,5,optional=true))
    private val availability = mutableMapOf<String,String>()
    private val samples = mutableMapOf<String,Sample>()
    private val times = mutableMapOf<String,ArrayDeque<Long>>()
    private var link: DashboardLink? = null
    private var header = ""
    private var nextConnect = 0L
    private var firstConnection = true
    private var message = "Live"
    private var messageUntil = 0L
    private var stage = "connect"
    private var lastChannel = ""
    private var headerCostMs=10.0
    private var coolantIntervalMs=1000L
    private var nextPolicyAt=0L
    private var nextDeferLogAt=0L
    private var engineBatch=true
    private var batchCostMs=25.0
    private val batchHint=ReplyHint()
    private val batchPids=mapOf(0x0C to 2,0x0D to 1)
    private fun status(text: String) { message=text;messageUntil=now()+5000 }
    private fun emit() = publish(DashState(samples.toMap(),if(now()<messageUntil)message else "Live",demo,availability.toMap(),coolantIntervalMs))
    private fun sample(key: String,bytes: ByteArray,at: Long) {
        val window=times.getOrPut(key){ArrayDeque()}
        val windowMs=if(key=="coolant")15000 else 3000
        window.addLast(at);while(window.size>1 && at-window.first()>windowMs)window.removeFirst()
        val hz=if(window.size>1)(window.size-1)*1000.0/(at-window.first()).coerceAtLeast(1)else 0.0
        samples[key]=Sample(bytes,at,hz)
    }
    fun step() {
        if(now()<nextConnect){sleep(minOf(50,nextConnect-now()));return}
        if(link==null) {
            try {
                stage="connect";status("Connecting…");emit();link=open()
                // Do not reboot the adapter on every reconnect. Reapply all required settings.
                val setup=listOf(if(firstConnection)"ATZ" else "ATPC","ATE0","ATL0","ATH1","ATAT1","ATST32","ATSP6")
                for(cmd in setup) {
                    stage=cmd
                    val reply=link!!.query(cmd,"dashboard_setup",6000)
                    if(cmd!="ATZ" && cmd!="ATPC" && !reply.contains("OK"))throw IOException("Setup rejected: $cmd (${Protocol.outcome(reply)})")
                }
                stage="ATS0"
                val compact=link!!.query("ATS0","dashboard_setup",2500).contains("OK")
                if(!compact && !link!!.query("ATS1","dashboard_setup",2500).contains("OK"))throw IOException("Cannot configure response spacing")
                event("dashboard_output_format",mapOf("compact" to compact))
                firstConnection=false;header="";times.clear();batchHint.reset();tasks.forEach{it.hint.reset();if(it.due==0L)it.due=now()};status("Connected");event("dashboard_connected",emptyMap())
            }catch(e: IOException){reconnect(e);return}
        }
        val start=now()
        val foreground=tasks.filter{!it.optional && it.key!="coolant"}
        val coolant=tasks.first{it.key=="coolant"}
        if(start>=nextPolicyAt) {
            nextPolicyAt=start+1000
            val active=foreground.filter{it.failures==0}
            val load=if(engineBatch)active.filter{it.key!="speed" && it.key!="rpm"}.sumOf{it.costMs/it.interval}+batchCostMs/200
                else active.sumOf{it.costMs/it.interval}
            val late=active.maxOfOrNull{(start-it.due).coerceAtLeast(0)}?:0
            val interval=when{load>=0.9 || late>=100->5000L;load>=0.7 || late>=25->2000L;else->1000L}
            if(interval!=coolantIntervalMs) {
                coolantIntervalMs=interval
                if(coolant.failures==0)coolant.lastStarted?.let{coolant.due=it+interval}
                event("dashboard_coolant_policy",mapOf("interval_ms" to interval,"foreground_load" to load,"foreground_late_ms" to late))
                emit()
            }
        }
        val ready=tasks.filter{it.due<=start}
        if(ready.isEmpty()){sleep(minOf(tasks.minOf{it.due}-start,25));return}
        // Reserve due speed reads under load, but never starve both pedal streams with consecutive speed reads.
        val core=ready.filter{!it.optional && it.key!="coolant"}
        val candidate=ready.firstOrNull{it.optional}
        // Body reads wait for initial motion attempts; forced probes below prevent starvation under load.
        val primaryReady=foreground.all{samples.containsKey(it.key) || availability.containsKey(it.key)}
        // Keep the speed/RPM pair, but coolant must pass its background budget.
        val enginePair=if(lastChannel=="speed" || lastChannel=="rpm")core.firstOrNull{
            it.key=="rpm" && it.key!=lastChannel && it.header==header
        }else null
        val coolantReady=coolant in ready && primaryReady
        val waiting=(start-coolant.due).coerceAtLeast(0)
        val sameHeader=header==coolant.header
        // Include switching away again, plus a small guard. Estimates cannot guarantee a deadline.
        val coolantBudget=coolant.queryMs+(if(sameHeader)0.0 else 2*headerCostMs)+5
        val slack=(foreground.minOf{it.due}-start).toDouble()
        val fits=coolantReady && core.isEmpty() && slack>=coolantBudget && (sameHeader || waiting>=250)
        // Under saturation allow an occasional refresh only while already at the engine address.
        val forced=coolantReady && sameHeader && waiting>=2000
        // Optional never means never: probe once promptly, then bound starvation under load.
        val indicatorForced=candidate!=null && primaryReady &&
            start-candidate.due >= if(candidate.lastStarted==null)0 else 1500
        if(coolantReady && !fits && !forced && start>=nextDeferLogAt) {
            nextDeferLogAt=start+1000
            event("dashboard_coolant_deferred",mapOf("waiting_ms" to waiting,"slack_ms" to slack,"estimated_budget_ms" to coolantBudget,
                "same_header" to sameHeader,"interval_ms" to coolantIntervalMs))
        }
        val task=candidate?.takeIf{indicatorForced && (!forced || start-it.due>waiting)}
            ?: coolant.takeIf{forced}
            ?: core.firstOrNull{it.key=="speed" && lastChannel!="speed"}
            ?: enginePair
            ?: coolant.takeIf{fits}
            ?: candidate?.takeIf{primaryReady && (core.isEmpty() || (core.all{start-it.due<100} && start-it.due>=500))}
            ?: core.maxByOrNull{start-it.due}
        if(task==null){sleep(minOf(25,foreground.minOf{it.due}-start).coerceAtLeast(1));return}
        var headerMs=0L
        var wire=task.command
        try {
            task.lastStarted=start
            if(header!=task.header) {
                stage="${task.key} header"
                val reply=link!!.query("ATSH${task.header}","dashboard_setup",2500)
                if(!reply.contains("OK"))throw IOException("Header rejected: ${task.header}")
                header=task.header;headerMs=now()-start
                headerCostMs=headerCostMs*.7+headerMs*.3
            }
            if(engineBatch && (task.key=="speed" || task.key=="rpm")) {
                pollEngineBatch(start,headerMs)
                return
            }
            stage="${task.key} ${task.command}"
            wire=task.hint.command(task.command)
            val queryStart=now()
            val raw=link!!.query(wire,"dashboard_${task.key}",if(task.optional || task.key=="coolant")1200 else 2500)
            task.queryMs=task.queryMs*.7+(now()-queryStart)*.3
            task.costMs=task.costMs*.7+(now()-start)*.3
            lastChannel=task.key
            event("dashboard_poll_timing",mapOf("channel" to task.key,"command" to task.command,"wire_command" to wire,
                "header_ms" to headerMs,"query_ms" to now()-queryStart,"total_ms" to now()-start,
                "late_ms" to (start-task.due).coerceAtLeast(0),"coolant_interval_ms" to coolantIntervalMs,
                "coolant_forced_refresh" to (task.key=="coolant" && forced),"indicator_forced_probe" to (task.optional && indicatorForced)))
            val bytes=Protocol.data(raw,task.command)
            if(bytes==null || bytes.size<task.size) {
                val hinted=wire!=task.command
                val switched=fallbackEngine(task,Protocol.outcome(raw))
                if(hinted){task.hint.reject();event("dashboard_reply_hint_disabled",mapOf("channel" to task.key,"reason" to Protocol.outcome(raw)))}
                fail(task,"${Protocol.outcome(raw)} / short or unmatched payload",hinted || (switched && task.key=="coolant"))
            }else{
                val at=now();sample(task.key,bytes,at);task.failures=0;availability[task.key]="Live"
                task.hint.observe(raw,task.command)
                // Temperatures arrive with throttle/gear. Publish them at 1 Hz without another ECU read.
                if(task.key=="tcm" && (samples["clutch"]==null || at-samples.getValue("clutch").at>=1000))sample("clutch",bytes,at)
                task.due=maxOf(start+(if(task.key=="coolant")coolantIntervalMs else task.interval),at)
            }
            emit()
        }catch(e: IOException){
            if(e.message=="Capture stopped")throw e
            val hinted=wire!=task.command
            val switched=fallbackEngine(task,e.toString())
            if(hinted){task.hint.reject();event("dashboard_reply_hint_disabled",mapOf("channel" to task.key,"reason" to e.toString()))}
            lastChannel=task.key
            fail(task,e.message?:"I/O error",hinted || (switched && task.key=="coolant"));emit()
            event("dashboard_recovery_attempt",mapOf("stage" to stage,"error" to e.toString()))
            // A timed-out ECU is not necessarily a disconnected Bluetooth socket.
            if(try{link?.recover()==true}catch(_:IOException){false}){header="";status("Adapter resynced · ${task.key} backed off");event("dashboard_resynced",mapOf("channel" to task.key));emit()}
            else reconnect(e)
        }
    }
    private fun pollEngineBatch(start:Long,headerMs:Long) {
        val logical="010C0D";val wire=batchHint.command(logical);val queryStart=now()
        try {
            stage="engine batch $logical"
            val raw=link!!.query(wire,"dashboard_engine_batch",1200)
            val decoded=Protocol.mode01Values(raw,batchPids)
            event("dashboard_poll_timing",mapOf("channel" to "engine_batch","command" to logical,"wire_command" to wire,
                "header_ms" to headerMs,"query_ms" to now()-queryStart,"total_ms" to now()-start))
            if(decoded==null) {
                disableBatch(Protocol.outcome(raw)+" / incomplete PID set")
                return
            }
            val at=now();batchCostMs=batchCostMs*.7+(at-start)*.3
            for((key,pid) in listOf("rpm" to 0x0C,"speed" to 0x0D)) {
                sample(key,decoded.getValue(pid),at);availability[key]="Live"
                tasks.first{it.key==key}.apply{failures=0;lastStarted=start;due=maxOf(start+interval,at)}
            }
            batchHint.observe(raw,logical,true)
            lastChannel="engine_batch";emit()
        }catch(e:IOException) {
            if(e.message=="Capture stopped")throw e
            disableBatch(e.message?:"I/O error")
            if(try{link?.recover()==true}catch(_:IOException){false}){header="";event("dashboard_resynced",mapOf("channel" to "engine_batch"))}
            else reconnect(e)
        }
    }
    private fun disableBatch(reason:String) {
        engineBatch=false;batchHint.reject()
        tasks.filter{it.key=="speed" || it.key=="rpm"}.forEach{it.due=now()}
        event("dashboard_engine_batch_disabled",mapOf("reason" to reason));emit()
    }
    private fun fallbackEngine(task:Task,reason:String):Boolean {
        if(task.key in engineChannels && task.header=="7E0") {
            tasks.filter{it.key in engineChannels}.forEach{it.header="7DF";it.hint.reset()}
            batchHint.reset()
            event(if(task.key=="speed")"dashboard_speed_fallback"else "dashboard_engine_fallback",mapOf("channel" to task.key,"from" to "7E0","to" to "7DF","reason" to reason))
            return true
        }
        return false
    }
    private fun fail(task: Task,reason: String,fastRetry:Boolean=false) {
        task.failures++;samples.remove(task.key);times.remove(task.key)
        if(task.key=="tcm"){samples.remove("clutch");times.remove("clutch")}
        task.due=now()+when{fastRetry->100;task.optional || task.key=="coolant"->minOf(120000,30000L*task.failures);else->minOf(5000,1000L*task.failures)}
        availability[task.key]="Unavailable: $reason"
        if(!task.optional)status("${task.key}: $reason")
        event("dashboard_channel_failure",mapOf("channel" to task.key,"stage" to stage,"reason" to reason,"retry_ms" to task.due-now()))
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
    companion object { const val COOLANT_MAX_AGE_MS=7500L }
}
