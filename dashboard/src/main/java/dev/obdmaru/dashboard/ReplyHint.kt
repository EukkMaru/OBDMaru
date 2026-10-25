package dev.obdmaru.dashboard

import dev.obdmaru.recorder.Protocol

/** Learn complete single-ECU ISO-TP frame counts before using ELM's response-count suffix.
 * Never guess one frame for a multi-frame reply. Periodically recheck with an unrestricted read.
 */
class ReplyHint {
    private var shape: Pair<Int,Int>? = null
    private var consecutive=0
    private var successes=0
    private var disabled=false
    fun command(logical:String):String = if(!disabled && consecutive>=3 && successes%30!=0)
        logical+shape!!.first.toString(16).uppercase() else logical
    fun reject(){disabled=true}
    fun reset(){shape=null;consecutive=0;successes=0;disabled=false}
    fun observe(raw:String,logical:String,valid:Boolean=Protocol.data(raw,logical)!=null) {
        if(disabled)return
        val payload=Protocol.payloads(raw).singleOrNull()
        val lines=Protocol.frames(raw)
        val expected=payload?.let {if(it.size<=7)1 else 1+(it.size-6+6)/7}
        if(payload==null || !valid || lines.size!=expected || lines.map{it.header}.distinct().size!=1 || lines.size !in 1..15) {
            shape=null;consecutive=0;return
        }
        val candidate=lines.size to payload.size
        consecutive=if(candidate==shape)consecutive+1 else 1
        shape=candidate;successes++
    }
}
