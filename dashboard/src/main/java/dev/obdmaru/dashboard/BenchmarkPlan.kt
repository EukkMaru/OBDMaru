package dev.obdmaru.dashboard

import dev.obdmaru.recorder.Protocol
import java.io.IOException

/** Parked, bounded, read-only experiments. No guessed reply counts, writes, baud or FC changes. */
class BenchmarkPlan(
    private val link:DashboardLink,
    private val nano:()->Long,
    private val sleep:(Long)->Unit,
    private val active:()->Boolean,
    private val event:(String,Map<String,Any?>)->Unit,
    private val progress:(String)->Unit
) {
    data class Query(val header:String?,val command:String,val size:Int=0,val pids:Map<Int,Int>?=null) {
        fun valid(raw:String,headers:Boolean):Boolean {
            if(header==null) {
                if('>' !in raw || Protocol.outcome(raw)!="response")return false
                val text=raw.uppercase()
                return when(command) {
                    "ATI"->listOf("ELM","STN","OBD").any{text.contains(it)}
                    "ATRV"->Regex("[0-9]+\\.[0-9]+\\s*V").containsMatchIn(text)
                    "ATDP"->listOf("ISO","CAN","SAE","USER").any{text.contains(it)}
                    "ATPPS"->Regex("[0-9A-F]{2}:[0-9A-F]{2}").containsMatchIn(text)
                    else->false
                }
            }
            if(!headers) {
                // ATH0/CAF1 single-frame Mode 01 only. Never assume this parses multi-frame output.
                if(!command.startsWith("01") || command.length!=4 || size>4)return false
                val prefix="41"+command.drop(2)
                return raw.uppercase().split('\r','\n').any {
                    val hex=it.trim().replace(Regex("\\s+"),"")
                    hex.length==prefix.length+size*2 && hex.startsWith(prefix) && Regex("[0-9A-F]+").matches(hex)
                }
            }
            return if(pids!=null)Protocol.mode01Values(raw,pids)!=null
                else (Protocol.data(raw,command)?.size?:-1)>=size
        }
    }
    data class Phase(val id:String,val label:String,val queries:List<Query>,val settings:List<String> = emptyList(),
        val hints:Boolean=false,val repeat:Int=16,val redundantHeader:Boolean=false,val gapMs:Long=0,val headers:Boolean=true)
    private var header=""
    private var compact=true
    private var guardHeader="7E0"
    private var phaseNumber=0

    private fun checkActive(){if(!active())throw InterruptedException("Benchmark stopped")}
    private fun emit(type:String,vararg fields:Pair<String,Any?>)=event(type,mapOf(*fields))
    private fun query(command:String,phase:String,timeout:Long=2500):String {
        checkActive()
        return try {link.query(command,phase,timeout)}catch(e:IOException){
            checkActive()
            emit("benchmark_io_error","command" to command,"phase" to phase,"error" to e.toString())
            if(!link.recover())throw IOException("Command boundary lost; benchmark stopped",e)
            throw Recovered(e)
        }
    }
    private class Recovered(cause:Exception):IOException("Recovered after request failure",cause)
    private fun setting(command:String,phase:String="benchmark_setup"):Boolean = query(command,phase).contains("OK")
    private fun address(next:String,phase:String,force:Boolean=false) {
        if(force || header!=next) {
            if(!setting("ATSH$next",phase))throw IOException("Address setting rejected: $next")
            header=next
        }
    }
    fun initialize() {
        progress("Setting up parked bottleneck test…")
        query("ATZ","benchmark_setup",6000)
        for(cmd in listOf("ATE0","ATL0","ATH1","ATAT1","ATST32","ATSP6","ATCAF1","ATCFC1"))
            if(!setting(cmd))throw IOException("Required setting rejected: $cmd")
        compact=setting("ATS0")
        if(!compact && !setting("ATS1"))throw IOException("Cannot configure output spacing")
        header=""
        emit("benchmark_start","plan_version" to 1,"default_compact" to compact,
            "timing_scope" to "Phone RFCOMM reads only; ECU/CAN/radio timing cannot be separated exactly",
            "excluded" to "No UART baud writes, protocol parameter writes, forced ISO-TP flow-control, actuator commands or unbounded monitoring")
        var parked=false
        for(candidate in listOf("7E0","7DF")) {
            address(candidate,"benchmark_guard")
            val bytes=Protocol.data(query("010D","benchmark_guard"),"010D")
            if(bytes?.size==1){
                if(bytes[0].toInt()!=0)throw IOException("Vehicle is moving; park before running this test")
                guardHeader=candidate;parked=true;break
            }
        }
        if(!parked)throw IOException("Cannot verify zero speed; parked test was not started")
    }
    private fun baseline() {
        for(cmd in listOf("ATE0","ATL0",if(compact)"ATS0"else "ATS1","ATH1","ATAT1","ATST32"))
            if(!setting(cmd))throw IOException("Could not restore baseline setting: $cmd")
        header=""
    }
    private fun parked() {
        address(guardHeader,"benchmark_guard")
        val bytes=Protocol.data(query("010D","benchmark_guard"),"010D")
        if(bytes?.size!=1 || bytes[0].toInt()!=0)throw IOException("Zero speed could not be verified; test stopped")
    }
    /** Settings and guard requests are outside the timed sample window and retained in the raw log. */
    fun run(phases:List<Phase> = phases()) {
        initialize()
        for(phase in phases) {
            checkActive();phaseNumber++
            progress("Test $phaseNumber/${phases.size+1}: ${phase.label}")
            emit("benchmark_phase_setup","id" to phase.id,"number" to phaseNumber,"label" to phase.label)
            try {
                baseline();parked()
                var supported=true
                for(cmd in phase.settings)if(!setting(cmd,"benchmark_config:${phase.id}")) {supported=false;break}
                if(!supported){emit("benchmark_phase_skip","id" to phase.id,"reason" to "setting rejected");continue}
                runPhase(phase)
            }catch(e:Recovered) {
                emit("benchmark_phase_abort","id" to phase.id,"reason" to e.toString())
            }
        }
        baseline();parked()
        emit("benchmark_plan_end","phases" to phases.size)
    }
    private fun runPhase(p:Phase) {
        val name="bench:${p.id}"
        val hint=p.queries.distinct().associateWith{ReplyHint()}
        val start=nano()
        emit("benchmark_phase_start","id" to p.id,"label" to p.label,"number" to phaseNumber,
            "settings" to p.settings.joinToString(","),"hints" to p.hints,"repeat" to p.repeat,
            "query_count_per_repeat" to p.queries.size,"gap_ms" to p.gapMs,"headers" to p.headers,
            "redundant_header" to p.redundantHeader,"warmup_repeats" to 3)
        var failures=0;var validCount=0;var measured=0;var completed=false
        try {
            for(i in 0 until p.repeat+3)for(q in p.queries) {
                checkActive()
                val begin=nano()
                if(q.header!=null)address(q.header,name,p.redundantHeader)
                val afterHeader=nano()
                val h=hint.getValue(q)
                val wire=if(p.hints && p.headers)h.command(q.command)else q.command
                val raw=query(wire,name)
                val afterQuery=nano()
                val valid=q.valid(raw,p.headers)
                if(p.hints && p.headers) {
                    if(!valid && wire!=q.command)h.reject()
                    else h.observe(raw,q.command,valid)
                }
                if(p.gapMs>0)sleep(p.gapMs)
                if(i>=3){measured++;if(valid)validCount++}
                emit("benchmark_sample","id" to p.id,"iteration" to i,"warmup" to (i<3),
                    "header" to q.header,"command" to q.command,"wire_command" to wire,"valid" to valid,
                    "outcome" to Protocol.outcome(raw),"ascii_bytes" to raw.toByteArray(Charsets.US_ASCII).size,
                    "can_frame_count" to if(p.headers)Protocol.frames(raw).size else null,
                    "header_ms" to (afterHeader-begin)/1e6,"query_ms" to (afterQuery-afterHeader)/1e6,
                    "cycle_ms" to (nano()-begin)/1e6)
                failures=if(valid)0 else failures+1
                if(failures>=3) {emit("benchmark_phase_abort","id" to p.id,"reason" to "three consecutive invalid replies");return}
            }
            completed=true
        }finally {
            emit("benchmark_phase_end","id" to p.id,"elapsed_ms" to (nano()-start)/1e6,
                "measured_samples" to measured,"valid_samples" to validCount,"completed" to completed)
        }
    }
    companion object {
        fun phases():List<Phase> {
            val speed=Query("7E0","010D",1)
            val rpm=Query("7E0","010C",2)
            val pedal=Query("7E0","0149",1)
            val coolant=Query("7E0","0105",1)
            val tcm=Query("7E1","2201A4",34)
            val abs=Query("7D1","22C101",39)
            val result=mutableListOf<Phase>()
            fun add(id:String,label:String,q:List<Query>,settings:List<String> = emptyList(),hints:Boolean=false,
                repeat:Int=16,redundant:Boolean=false,gap:Long=0,headers:Boolean=true) {
                result+=Phase(id,label,q,settings,hints,repeat,redundant,gap,headers)
            }
            for(cmd in listOf("ATI","ATRV","ATDP","ATPPS"))add("local_$cmd","Adapter only: $cmd",listOf(Query(null,cmd)),repeat=24)
            for(q in listOf(speed,rpm,pedal,coolant,tcm,Query("7E1","2201A5",26),abs,
                Query("7E0","22ED06",34),Query("770","22BC08",5),speed.copy(header="7DF"))) {
                val id="${q.header}_${q.command}"
                add("${id}_plain","${q.header}/${q.command} unrestricted",listOf(q))
                add("${id}_hint","${q.header}/${q.command} learned count",listOf(q),hints=true,repeat=24)
            }
            // A/B/A groups: repeat the baseline to expose drift rather than silently attributing it.
            for((name,settings) in listOf("spaces" to listOf("ATS1"),"compact" to listOf("ATS0"),
                "linefeeds" to listOf("ATL1"),"echo" to listOf("ATE1"))) {
                add("format_${name}_a","Formatting $name: baseline",listOf(tcm),hints=true)
                add("format_${name}_b","Formatting $name: changed",listOf(tcm),settings,hints=true)
                add("format_${name}_a2","Formatting $name: baseline repeat",listOf(tcm),hints=true)
            }
            add("headers_a","Single frame with headers",listOf(speed))
            add("headers_b","Single frame without headers",listOf(speed),listOf("ATH0"),headers=false)
            add("headers_a2","Single frame headers restored",listOf(speed))
            for(adaptive in listOf(0,1,2))for(timeout in listOf("08","20","64")) {
                val settings=listOf("ATAT$adaptive","ATST$timeout")
                add("wait_${adaptive}_${timeout}_plain","Adaptive $adaptive, timeout $timeout: plain",listOf(speed,tcm),settings,repeat=8)
                add("wait_${adaptive}_${timeout}_hint","Adaptive $adaptive, timeout $timeout: count",listOf(speed,tcm),settings,hints=true,repeat=8)
            }
            add("batch_separate","Engine values separately",listOf(rpm,speed,pedal,coolant),hints=true)
            add("batch_pair","Engine RPM/speed pair",listOf(Query("7E0","010C0D",pids=mapOf(0x0C to 2,0x0D to 1))),hints=true)
            add("batch_four","Four engine PIDs together",listOf(Query("7E0","010C0D4905",pids=mapOf(0x0C to 2,0x0D to 1,0x49 to 1,0x05 to 1))),hints=true)
            add("batch_separate_repeat","Engine separate baseline repeat",listOf(rpm,speed,pedal,coolant),hints=true)
            add("header_cached","Same TCM address reused",listOf(tcm),hints=true,repeat=24)
            add("header_redundant","Set same TCM address every read",listOf(tcm),hints=true,repeat=24,redundant=true)
            add("header_cached_repeat","Same TCM address baseline repeat",listOf(tcm),hints=true,repeat=24)
            add("switch_alternating","Alternate TCM / ABS / engine",listOf(tcm,abs,speed),hints=true,repeat=12)
            add("switch_grouped","Group TCM / ABS / engine reads",List(4){tcm}+List(4){abs}+List(4){speed},hints=true,repeat=3)
            add("switch_alternating_repeat","Alternate addresses baseline repeat",listOf(tcm,abs,speed),hints=true,repeat=12)
            for(gap in listOf(0L,5L,15L,30L,0L).withIndex())add("gap_${gap.index}_${gap.value}","TCM post-prompt gap ${gap.value} ms",listOf(tcm),hints=true,repeat=24,gap=gap.value)
            add("local_ATI_end","Adapter-only baseline repeat",listOf(Query(null,"ATI")),repeat=24)
            return result
        }
    }
}
