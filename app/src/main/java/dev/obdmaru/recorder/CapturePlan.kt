package dev.obdmaru.recorder

import android.os.SystemClock
import org.json.JSONArray
import java.io.IOException

enum class CaptureMode { MAGIC, GUIDED }
data class LiveSample(val speedKph: Int? = null, val speedAtMs: Long = 0, val rpm: Double? = null,
                      val pedalRawPercent: Double? = null)

class CapturePlan(private val elm: ElmTransport, private val log: SessionLog,
                  private val active: () -> Boolean, private val status: (String) -> Unit,
                  private val probes: List<Pair<String, String>> = emptyList(),
                  private val mode: CaptureMode = CaptureMode.MAGIC,
                  private val sample: (LiveSample) -> Unit = {}, private val tick: () -> Unit = {}) {
    private var header = ""
    private var live = LiveSample()
    private var count = 0
    private val timing = mutableMapOf<String, Long>()
    private val intervals = mutableMapOf<String, MutableList<Long>>()
    private fun now() = SystemClock.elapsedRealtime()
    private fun ask(c: String, phase: String, timeout: Long = 6000): String {
        if (!active()) throw IOException("Capture stopped")
        tick()
        return elm.query(c, phase, timeout)
    }
    fun run() {
        status("Identifying adapter")
        ask("ATZ", "adapter", 12000)
        listOf("ATE0", "ATL0", "ATS1", "ATH1", "ATAT1", "ATST96", "ATSP0").forEach {
            val r = ask(it, "setup")
            if (!r.contains("OK")) throw IllegalStateException("Required adapter setting rejected: $it: $r")
        }
        listOf("ATI", "AT@1", "AT@2", "ATRV").forEach { ask(it, "adapter") }
        status("Discovering standard OBD readings")
        val pids = sortedSetOf<Int>()
        for (base in 0..0xE0 step 0x20) {
            val raw = ask("01%02X".format(base), "supported_pids", 15000)
            pids.addAll(Protocol.supported(raw, 1, base).filter { it % 32 != 0 })
        }
        val protocol = ask("ATDPN", "protocol")
        ask("ATDP", "protocol")
        val can11 = Regex("(?:A)?[68]").matches(protocol.replace(">", "").trim())
        log.event("discovery", "supported_mode01" to JSONArray(pids.toList()))
        val scheduler = PollScheduler(if (mode == CaptureMode.GUIDED) 7 else 3)
        // Desired intervals. Actual cadence is measured and logged; bus/adapter throughput wins.
        fun task(h: String, c: String, ms: Long, fast: Boolean = true) {
            scheduler.add(PollTask(h, c, fast, ms))
        }
        val obd = if (can11) "7DF" else ""
        task(obd, "010D", if (mode == CaptureMode.GUIDED) 600 else 800)
        task(obd, "010C", 1000)
        task(obd, "0149", if (mode == CaptureMode.GUIDED) 450 else 650)
        task(obd, "014A", 2200)
        task(obd, "0105", 5000, false)
        task(obd, "010F", 10000, false)
        if (can11) {
            task("7E1", "2201A4", if (mode == CaptureMode.GUIDED) 400 else 600)
            task("7E1", "2201A5", 1500)
            if (mode == CaptureMode.GUIDED) task("7D1", "22C101", 400)
            task("7E0", "22ED06", if (mode == CaptureMode.GUIDED) 600 else 1500)
            task("7E0", "22ED04", if (mode == CaptureMode.GUIDED) 1800 else 4000)
            task("7E0", "22ED05", 3500)
            task("7E0", "22ED07", 4000, false)
        }
        val initial = mutableListOf<Pair<String, String>>()
        // Every supported standard channel is observed at least once in either mode.
        initial.addAll(pids.map { obd to "01%02X".format(it) })
        initial.addAll(listOf("0900", "0902", "0904", "0906", "090A", "03", "07", "0A", "020000").map { obd to it })
        if (can11) initial.addAll(probes + TCM.map { "7E1" to it })
        val neighbors = if (mode == CaptureMode.MAGIC && can11)
            (0..0x3F).map { "7E0" to "22ED%02X".format(it) } +
            (0x90..0x9F).map { "7E0" to "22ED%02X".format(it) } +
            (0xA0..0xAF).map { "7E1" to "2201%02X".format(it) } else emptyList()
        if (mode == CaptureMode.MAGIC) {
            initial.addAll((1..0xFF).filter { it % 32 != 0 }.map { obd to "01%02X".format(it) })
            initial.addAll(neighbors)
        }
        log.event("neighbor_discovery_plan", "requests" to JSONArray(neighbors.map { "${it.first}/${it.second}" }),
            "read_services_only" to true)
        initial.distinct().forEach { (h,c) -> scheduler.add(PollTask(h,c,false,15000,discovery=true)) }
        log.event("poll_plan", "mode" to mode.name, "fast_burst" to if (mode == CaptureMode.GUIDED) 7 else 3,
            "request_gap_ms" to 15, "zero_retry_ms" to 120000, "failed_retry_ms" to 60000,
            "initial_jobs" to scheduler.size(), "discovery_jobs" to scheduler.remainingDiscovery(),
            "targets_are_not_guarantees" to true)
        var reportAt = now()
        var requestsAtReport = 0
        while (active()) {
            tick()
            val job = scheduler.next(now())
            if (job == null) { Thread.sleep(20); continue }
            if (job.header.isNotEmpty()) setHeader(job.header)
            val sent = now()
            val phase = if (job.discovery) "discovery" else if (job.fast) "live_fast" else "live_background"
            val raw = ask(job.command, phase, if (job.command.startsWith("09")) 12000 else 6000)
            val data = Protocol.data(raw, job.command)
            val positive = data != null
            val zero = data != null && data.isNotEmpty() && data.all { it == 0.toByte() }
            val completed = now()
            log.event("sample_classification", "source" to job.key, "positive" to positive, "all_zero_data" to zero,
                "diagnostic_data_bytes" to data?.size, "mode" to mode.name, "elapsed_ms" to completed-sent)
            if (mode == CaptureMode.GUIDED && data != null) {
                ControlObservation.fields(job.header,job.command,data)?.let { fields ->
                    log.event("guide_control_observation", "source" to job.key,
                        "sample_completed_ms" to completed, "values" to org.json.JSONObject(fields),
                        "meaning" to "raw owner-observed candidates; not confirmation of instructed actions",
                        "parking_brake_known" to false, "cruise_state_known" to false)
                }
            }
            // Judge only standardized speed/pedal readings, not inferred brake or cruise bits.
            if (data != null && job.command.startsWith("01")) {
                val a = data.getOrNull(0)?.toInt()?.and(255)
                when (job.command) {
                    "010D" -> if (a != null) live = live.copy(speedKph = a, speedAtMs = completed)
                    "010C" -> if (data.size >= 2) live = live.copy(rpm = (((data[0].toInt() and 255) shl 8) or (data[1].toInt() and 255))/4.0)
                    "0149" -> if (a != null) live = live.copy(pedalRawPercent = a*100.0/255)
                }
                sample(live)
            }
            timing.put(job.key, completed)?.let { previous -> intervals.getOrPut(job.key) { mutableListOf() }.add(completed-previous) }
            scheduler.complete(job, completed, positive, zero)
            if (job.discovery && positive && job.command.take(2) in setOf("01", "21", "22")) {
                // Revisit zeros infrequently rather than treating them as absent hardware.
                val interval = if (zero) 120000L else 15000L
                scheduler.add(PollTask(job.header,job.command,false,interval,nextMs=completed+interval))
            } else if (job.discovery && !positive && job.header == "7E1" && job.command in TCM) {
                scheduler.add(PollTask(job.header,job.command,false,60000,nextMs=completed+60000))
            }
            if (job.fast && !positive) job.nextMs = completed + 10000 // no unsupported hot loop
            count++
            if (completed-reportAt >= 5000) {
                val elapsed = completed-reportAt
                val rate = (count-requestsAtReport)*1000.0/elapsed
                val gaps = org.json.JSONObject()
                intervals.forEach { (key, xs) -> if (xs.isNotEmpty()) gaps.put(key, org.json.JSONObject()
                    .put("samples", xs.size).put("mean_ms", xs.average()).put("max_ms", xs.max()).put("min_ms", xs.min())) }
                log.event("poll_metrics", "requests_per_second" to rate, "remaining_discovery" to scheduler.remainingDiscovery(), "intervals" to gaps)
                status("${mode.name} · ${"%.1f".format(rate)} data requests/s · ${scheduler.remainingDiscovery()} probes left")
                intervals.clear(); requestsAtReport=count; reportAt=completed
            }
        }
    }
    private fun setHeader(h: String) {
        if (h == header) return
        val raw = ask("ATSH$h", "addressing")
        if (!raw.contains("OK")) throw IllegalStateException("Cannot select header $h: $raw")
        header = h
    }
    companion object {
        val TCM = listOf("2201A4", "2201A5", "2201A0", "21A0", "21A1")
    }
}
