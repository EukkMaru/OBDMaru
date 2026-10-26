package dev.obdmaru.dashboard

import java.io.IOException

interface BlinkerLink {
    fun query(command:String):String
    fun monitor(active:()->Boolean,tick:()->Unit,frame:(ByteArray)->Unit):String
}

/** Candidate fields from opendbc 1bce882c... hyundai_2015_ccan.dbc CGW1, not TL validation. */
data class BlinkerCandidate(val left:Int,val right:Int,val leftStalk:Int,val rightStalk:Int,val hazard:Int) {
    companion object {
        fun decode(b:ByteArray):BlinkerCandidate {
            require(b.size==8)
            fun u(i:Int)=b[i].toInt() and 255
            return BlinkerCandidate((u(2) shr 3) and 3,(u(7) shr 6) and 3,
                (u(5) shr 2) and 1,(u(5) shr 1) and 1,(u(4) shr 1) and 3)
        }
    }
}

class BlinkerTest(private val link:BlinkerLink,private val now:()->Long,
    private val requestedStage:()->Int,private val event:(String,Map<String,Any?>)->Unit,
    private val publish:(String)->Unit,private val ready:(Boolean)->Unit) {
    private var safeBoundary=true
    private fun query(command:String):String = try {link.query(command)}catch(e:Exception){safeBoundary=false;throw e}
    private fun ok(command:String) {
        val raw=query(command)
        if('>' !in raw || raw.split('\r','\n','>').none{it.trim()=="OK"})throw IOException("Required setting rejected: $command")
    }
    fun run():String {
        var stage=-1;var count=0;var lastAt=0L;var last: BlinkerCandidate?=null
        var start=0L;var lastPublish=Long.MIN_VALUE
        fun tick() {
            val selected=requestedStage().coerceIn(0,STAGES.size)
            if(selected!=stage) {
                stage=selected
                event("blinker_stage",mapOf("stage" to stage,"instruction" to STAGES.getOrNull(stage),
                    "meaning" to "user-requested stage marker; actual control operation not verified"))
                lastPublish=Long.MIN_VALUE
            }
            val at=now()
            if(lastPublish==Long.MIN_VALUE || at-lastPublish>=250) {
                lastPublish=at
                val values=last?.let{"Lamp raw L=${it.left} R=${it.right}\nStalk L=${it.leftStalk} R=${it.rightStalk} · hazard raw=${it.hazard}"}?:"No 0x541 frames received yet"
                val age=if(count>0)" · last ${at-lastAt} ms ago"else ""
                val hz=if(at>start)count*1000.0/(at-start)else 0.0
                publish("Parked blinker test · ${((120000-(at-start)).coerceAtLeast(0)/1000)}s left\n"+
                    "${stage+1}/7: ${STAGES.getOrNull(stage)?:"Finishing"}\n"+
                    "Hold each stage 5–10 seconds, then tap Next.\n$values\n$count frames · %.1f Hz%s\nCandidate values only; keep the car parked.".format(java.util.Locale.US,hz,age))
            }
        }
        try {
            publish("Configuring filtered silent monitor…")
            val identity=query("ATZ").uppercase()
            if('>' !in identity || listOf("ELM","OBD","STN").none{it in identity})throw IOException("Unrecognized adapter reset reply")
            for(cmd in listOf("ATE0","ATL0","ATH1","ATS0","ATSP6","ATCAF0","ATD1","ATCSM1","ATCRA541"))ok(cmd)
            event("blinker_test_start",mapOf("can_id" to "541","protocol" to "11-bit CAN 500k",
                "silent_requested" to true,"max_duration_ms" to 120000,
                "timestamp_scope" to "phone arrival, not CAN electrical time",
                "mapping" to "opendbc 1bce882c6330f8cc591f368f415ae5fa241ae225 hyundai_2015_ccan.dbc CGW1; unvalidated candidate"))
            start=now();ready(true)
            val reason=try {link.monitor({requestedStage()<STAGES.size},::tick) {bytes->
                last=BlinkerCandidate.decode(bytes);lastAt=now();count++
                val c=last!!
                event("blinker_frame",mapOf("can_id" to "541","data_hex" to bytes.joinToString(""){"%02X".format(it.toInt() and 255)},
                    "stage" to stage,"left_raw" to c.left,"right_raw" to c.right,
                    "left_stalk" to c.leftStalk,"right_stalk" to c.rightStalk,"hazard_raw" to c.hazard))
            }}catch(e:Exception){
                // monitor() guarantees a stop boundary only on successful return.
                safeBoundary=false;throw e
            }
            event("blinker_test_end",mapOf("reason" to reason,"frames" to count,"stages_requested" to requestedStage()+1))
            return if(count==0)"Test finished · no 0x541 frames. Share log." else "Test finished · $count frames saved. Share log."
        } finally {
            ready(false)
            if(safeBoundary) {
                // Reset discards the temporary filter/DLC/CAF settings; next normal Start initializes anew.
                val raw=query("ATZ").uppercase()
                val reset='>' in raw && listOf("ELM","OBD","STN").any{it in raw}
                event("blinker_adapter_reset",mapOf("success" to reset))
                if(!reset)throw IOException("Adapter reset not confirmed; unplug/reconnect before normal use")
            } else event("blinker_adapter_reset",mapOf("success" to false,"reason" to "serial boundary uncertain; socket will close; unplug/reconnect before normal use"))
        }
    }
    companion object {
        val STAGES=listOf("Indicators OFF; hazards OFF", "LEFT indicator ON; hazards OFF", "LEFT OFF; both indicators OFF",
            "RIGHT indicator ON; hazards OFF", "RIGHT OFF; both indicators OFF", "HAZARDS ON; stalk neutral", "HAZARDS OFF; both indicators OFF")
    }
}
