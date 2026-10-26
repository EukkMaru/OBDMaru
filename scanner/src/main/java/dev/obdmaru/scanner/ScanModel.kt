package dev.obdmaru.scanner

import dev.obdmaru.recorder.Protocol
import java.io.Reader
import java.util.Locale

data class ScanQuery(val command: String, val title: String)
data class EcuFinding(val ecu: String, val status: String, val lines: List<String>, val codes: List<String> = emptyList())
data class ScanReply(val query: ScanQuery, val findings: List<EcuFinding>, val problems: List<String>) {
    val needsRecovery get() = problems.any { it.startsWith("Incomplete") || it.startsWith("Invalid CAN") } ||
        findings.any { f -> f.status!="Read" && f.lines.any { it.endsWith("NRC 78") } }
    val text get() = buildString {
        appendLine("${query.title} · ${query.command}")
        findings.forEach { f ->
            appendLine("ECU ${f.ecu} · ${f.status}")
            f.lines.forEach { appendLine(it) }
        }
        problems.forEach { appendLine(it) }
    }.trim()
}

/** Offline descriptions are community reference data, never assertions of TL-specific applicability. */
class DtcCatalog(private val descriptions: Map<String, String>) {
    fun description(code: String): String? = if (isGeneric(code)) descriptions[code] else null
    fun explain(code: String): String = description(code)?.let { "$code — $it\nCommunity generic description" }
        ?: "$code — ${if (isGeneric(code)) "Description unavailable" else "Manufacturer-controlled / reserved code"}\nExact Hyundai TL definition not bundled"
    companion object {
        fun isGeneric(code: String): Boolean {
            if (!Regex("[PCBU][0-3][0-9A-F]{3}").matches(code)) return false
            return when (code[0]) {
                'P' -> code[1] in "02" || (code[1]=='3' && code.substring(2).toInt(16)>=0x400)
                else -> code[1] in "03"
            }
        }
        fun load(reader: Reader): DtcCatalog = DtcCatalog(reader.buffered().useLines { lines ->
            lines.filter { !it.startsWith("#") && it.contains('\t') }.associate { line ->
                val p=line.split('\t', limit=2); p[0] to p[1]
            }
        })
    }
}

object ScanPlan {
    val queries = listOf(
        ScanQuery("0101", "Check-engine light & readiness"),
        ScanQuery("03", "Stored emissions codes"),
        ScanQuery("07", "Pending emissions codes"),
        ScanQuery("0A", "Permanent emissions codes"),
        ScanQuery("020200", "Freeze frame 0 · triggering code"),
        ScanQuery("020400", "Freeze frame 0 · engine load"),
        ScanQuery("020500", "Freeze frame 0 · coolant"),
        ScanQuery("020B00", "Freeze frame 0 · intake pressure"),
        ScanQuery("020C00", "Freeze frame 0 · RPM"),
        ScanQuery("020D00", "Freeze frame 0 · speed"),
        ScanQuery("020F00", "Freeze frame 0 · intake temperature"),
        ScanQuery("021000", "Freeze frame 0 · air flow"),
        ScanQuery("021100", "Freeze frame 0 · throttle position"),
        ScanQuery("022300", "Freeze frame 0 · fuel rail pressure"),
        ScanQuery("024200", "Freeze frame 0 · ECU supply voltage")
    )
    // Read-only adapter setup + standard services. No clear/write/actuator/session/security requests.
    val setup = listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH1", "ATD0", "ATSP6", "ATCAF1", "ATCFC1", "ATAL", "ATAT0", "ATST64", "ATSH7DF", "ATAR")
    fun code(high: Int, low: Int): String = "%c%04X".format("PCBU"[(high and 255) shr 6], ((high and 63) shl 8) or (low and 255))
}

/** CAN 11-bit / 500k scanner, with complete ISO-TP assembly scoped to a single request AND ECU. */
class ScanDecoder(private val catalog: DtcCatalog) {
    private data class Assembly(val total: Int, val bytes: MutableList<Int>, var sequence: Int = 1)
    fun decode(query: ScanQuery, raw: String): ScanReply {
        val problems=mutableListOf<String>()
        val messages=linkedMapOf<String, MutableList<List<Int>>>()
        val pending=mutableMapOf<String, Assembly>()
        val broken=mutableSetOf<String>()
        // An echoed hex command can otherwise look like a CAN header. Echo is disabled as well.
        val clean=raw.split('\r','\n').filter { it.trim().replace(" ", "").uppercase()!=query.command }.joinToString("\r")
        val frames=Protocol.frames(clean)
        fun invalid(id: String, reason: String) { broken.add(id); pending.remove(id); problems.add("Invalid CAN reply from $id: $reason") }
        frames.forEach { f ->
            val id=f.header; val b=f.bytes
            if(id.length!=3 || id.toInt(16) !in 0x7E8..0x7EF) {
                problems.add("Unexpected CAN source $id; excluded from standard emissions results"); return@forEach
            }
            if(id in broken) return@forEach
            when(b[0] shr 4) {
                0 -> {
                    val n=b[0] and 15
                    if(pending.containsKey(id) || n !in 1..7 || b.size<n+1) invalid(id,"invalid single-frame length or interrupted message")
                    else messages.getOrPut(id){mutableListOf()}.add(b.drop(1).take(n))
                }
                1 -> {
                    if(b.size!=8 || pending.containsKey(id)) invalid(id,"invalid first frame")
                    else {
                        val n=((b[0] and 15) shl 8) or b[1]
                        if(n<=7) invalid(id,"invalid multi-frame length")
                        else pending[id]=Assembly(n,b.drop(2).toMutableList())
                    }
                }
                2 -> {
                    val a=pending[id]
                    if(a==null || (b[0] and 15)!=a.sequence%16 || b.size<2) invalid(id,"missing or out-of-order frame")
                    else {
                        a.sequence++; a.bytes.addAll(b.drop(1))
                        if(a.bytes.size>=a.total) { messages.getOrPut(id){mutableListOf()}.add(a.bytes.take(a.total)); pending.remove(id) }
                        else if(b.size!=8) invalid(id,"short intermediate frame")
                    }
                }
                else -> invalid(id,"unexpected frame type")
            }
        }
        pending.forEach { (id,a) -> broken.add(id); problems.add("Incomplete reply from $id: ${a.bytes.size}/${a.total} payload bytes; no result inferred") }
        val expected=query.command.take(2).toInt(16)
        val findings=messages.filterKeys { it !in broken }.map { (id,packets) ->
            val positive=packets.filter { it.firstOrNull()==expected+0x40 }.distinct()
            when {
                positive.size>1 -> EcuFinding(id,"Unresolved",listOf("Conflicting replies in one request; no result inferred"))
                positive.size==1 -> interpret(id,query,positive.single())
                else -> {
                    val negatives=packets.filter { it.size==3 && it[0]==0x7F && it[1]==expected }
                    EcuFinding(id,"Unavailable",if(negatives.isEmpty())listOf("No matching positive response") else negatives.map { "ECU negative response: NRC %02X".format(it[2]) })
                }
            }
        }
        val outcome=Protocol.outcome(raw.uppercase())
        if(outcome!="response") problems.add("Adapter: ${outcome.replace('_',' ')}; this does not mean zero faults")
        if(findings.isEmpty() && problems.isEmpty()) problems.add("No complete matching ECU reply; fault status unknown")
        return ScanReply(query,findings,problems)
    }
    private fun interpret(id: String, q: ScanQuery, p: List<Int>): EcuFinding {
        fun invalid(reason: String)=EcuFinding(id,"Unresolved",listOf("$reason; no result inferred"))
        return when(q.command) {
            "03", "07", "0A" -> {
                if(p.size<2) return invalid("Missing CAN DTC count")
                val count=p[1]; val end=2+count*2
                if(p.size<end || p.drop(end).any{it!=0}) return invalid("DTC count does not match payload length")
                val pairs=p.subList(2,end).chunked(2)
                if(pairs.any{it==listOf(0,0)}) return invalid("Zero placeholder included in advertised code count")
                val codes=pairs.map{ScanPlan.code(it[0],it[1])}
                EcuFinding(id,"Read",if(codes.isEmpty())listOf("ECU explicitly reported 0 codes in this category") else codes.map(catalog::explain),codes)
            }
            "0101" -> {
                if(p.size!=6 || p[1]!=1) return invalid("Unexpected readiness PID or length")
                EcuFinding(id,"Read",readiness(p.drop(2)))
            }
            else -> {
                val pid=q.command.substring(2,4).toInt(16)
                if(p.size<3 || p[1]!=pid || p[2]!=0) return invalid("Wrong freeze-frame PID or frame number")
                val b=p.drop(3)
                val length=if(pid in listOf(2,12,16,35,66))2 else 1
                if(b.size!=length) return invalid("Unexpected freeze-frame value length")
                val v=b[0]; val word=if(length==2)v*256+b[1] else v
                val value=when(pid) {
                    2 -> if(word==0) "No triggering code reported for frame 0" else catalog.explain(ScanPlan.code(b[0],b[1]))
                    4 -> fmt(word*100.0/255,"% engine load")
                    5,15 -> "${word-40} °C"
                    11 -> "$word kPa absolute"
                    12 -> fmt(word/4.0,"RPM")
                    13 -> "$word km/h"
                    16 -> fmt(word/100.0,"g/s")
                    17 -> fmt(word*100.0/255,"% throttle position")
                    35 -> "${word*10} kPa fuel rail pressure"
                    66 -> fmt(word/1000.0,"V ECU supply")
                    else -> return invalid("Unrecognized freeze-frame decoder")
                }
                EcuFinding(id,"Read",listOf(value),if(pid==2 && word!=0)listOf(ScanPlan.code(b[0],b[1]))else emptyList())
            }
        }
    }
    private fun fmt(v: Double, unit: String)=String.format(Locale.US,"%.2f %s",v,unit)
    private fun readiness(b: List<Int>): List<String> {
        val result=mutableListOf("Check-engine light requested: ${if(b[0] and 128!=0)"ON" else "OFF"}","Stored emissions DTC count: ${b[0] and 127}")
        val diesel=b[1] and 8!=0
        result.add("Readiness layout: ${if(diesel)"compression ignition (diesel)" else "spark ignition"}")
        listOf("Misfire", "Fuel system", "Comprehensive components").forEachIndexed { i,name ->
            if(b[1] and (1 shl i)!=0)result.add("$name: ${if(b[1] and (1 shl (i+4))!=0)"not complete" else "complete"}")
        }
        val monitors=if(diesel)mapOf(0 to "NMHC catalyst",1 to "NOx/SCR",3 to "Boost pressure",5 to "Exhaust gas sensor",6 to "Particulate filter",7 to "EGR/VVT")
            else mapOf(0 to "Catalyst",1 to "Heated catalyst",2 to "Evaporative system",3 to "Secondary air",4 to "A/C refrigerant",5 to "Oxygen sensor",6 to "Oxygen sensor heater",7 to "EGR/VVT")
        monitors.forEach { (i,name) -> if(b[2] and (1 shl i)!=0)result.add("$name: ${if(b[3] and (1 shl i)!=0)"not complete" else "complete"}") }
        result.add("Unsupported monitors omitted. Readiness is test completion, not proof of vehicle health.")
        return result
    }
}

object ScanReport {
    fun render(replies: List<ScanReply>, demo: Boolean, state: String): String = buildString {
        appendLine("OBDMaru Scanner 0.1.0${if(demo)" · SYNTHETIC DEMO" else ""}")
        appendLine("$state\nCoverage: standard emissions OBD-II, CAN 11-bit / 500 kbit/s.")
        appendLine("A missing reply means unknown, not zero faults. ABS/airbag/body scan coverage is not established.")
        appendLine("Descriptions: offline community generic reference; exact Hyundai TL manufacturer definitions are not bundled.")
        appendLine("Codes identify detected conditions, not a confirmed failed part. No fault clearing is performed.")
        appendLine("Completed queries: ${replies.size}/${ScanPlan.queries.size}\n")
        replies.forEach { appendLine(it.text); appendLine() }
        appendLine("Description source: Wal33D/dtc-database, MIT, revision 04c43d72e7db7197658b6f72fe582c5076d9eee8.")
        appendLine("https://github.com/Wal33D/dtc-database")
    }
}

/** One request at a time. Never advance past an incomplete response without a clean boundary. */
class ScanRunner(private val decoder: ScanDecoder) {
    fun run(query: (ScanQuery)->String, active: ()->Boolean, recover: ()->Boolean, emit: (ScanReply)->Unit) {
        for(q in ScanPlan.queries) {
            if(!active())throw InterruptedException("Scan stopped")
            val reply=decoder.decode(q,query(q))
            emit(reply)
            if(!active())throw InterruptedException("Scan stopped")
            if(reply.needsRecovery && !recover())throw java.io.IOException("Incomplete CAN reply; could not restore a clean request boundary")
        }
    }
}
