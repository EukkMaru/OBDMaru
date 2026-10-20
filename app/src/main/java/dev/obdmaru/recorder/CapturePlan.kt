package dev.obdmaru.recorder

import org.json.JSONArray

class CapturePlan(private val elm: ElmTransport, private val log: SessionLog,
                  private val active: () -> Boolean, private val status: (String) -> Unit,
                  private val probes: List<Pair<String, String>> = emptyList()) {
    private fun ask(c: String, phase: String, timeout: Long = 6000) = elm.query(c, phase, timeout)
    fun run() {
        status("Identifying adapter")
        ask("ATZ", "adapter", 12000)
        // Only adapter-local configuration and diagnostic read services are used.
        listOf("ATE0", "ATL0", "ATS1", "ATH1", "ATAT1", "ATST96", "ATSP0").forEach {
            val r = ask(it, "setup")
            if (!r.contains("OK")) throw IllegalStateException("Required adapter setting rejected: $it: $r")
        }
        listOf("ATI", "AT@1", "AT@2", "ATRV").forEach { ask(it, "adapter") }
        status("Discovering standard OBD readings")
        val pids = sortedSetOf<Int>()
        // Probe every support page once, even if a clone or ECU reports an inconsistent continuation bit.
        for (base in 0..0xE0 step 0x20) {
            val raw = ask("01%02X".format(base), "supported_pids", 15000)
            pids.addAll(Protocol.supported(raw, 1, base).filter { it % 32 != 0 })
        }
        val protocol = ask("ATDPN", "protocol")
        ask("ATDP", "protocol")
        log.event("discovery", "supported_mode01" to JSONArray(pids.toList()))
        for (c in listOf("0900", "0902", "0904", "0906", "090A", "03", "07", "0A", "020000"))
            ask(c, "vehicle_snapshot", 12000)
        // Also capture every standard Mode 01 PID once. Failed/undeclared reads are evidence.
        val undeclared = (1..0xFF).filter { it % 32 != 0 && it !in pids }
        val standard = pids.map { "01%02X".format(it) }.toMutableList()
        val enhanced = mutableListOf<String>()
        val can11 = Regex("(?:A)?[68]").matches(protocol.replace(">", "").trim())
        if (can11) {
            status("Investigating transmission read requests")
            setHeader("7E1")
            for (c in TCM) {
                val raw = ask(c, "tcm_discovery", 8000)
                if (Protocol.positive(raw, c)) enhanced.add(c)
            }
            setHeader("7DF")
        } else log.event("phase_skipped", "phase" to "tcm_discovery", "reason" to "Not confirmed 11-bit CAN", "protocol" to protocol)
        status("Recording live data + remaining discovery")
        var cursor = 0
        var cycle = 0
        var extraCursor = 0
        var standardCursor = 0
        val extraLive = mutableListOf<Pair<String, String>>()
        while (active()) {
            // Interleave extended discovery with useful telemetry immediately; don't spend the whole trip scanning.
            repeat(8) {
                if (cursor < undeclared.size && active()) {
                    val pid = undeclared[cursor++]
                    val cmd = "01%02X".format(pid)
                    val raw = ask(cmd, "undeclared_pid_probe")
                    if (Protocol.positive(raw, cmd)) standard.add(cmd)
                }
            }
            if (can11) {
                repeat(4) {
                    if (extraCursor < probes.size && active()) {
                        val (header, cmd) = probes[extraCursor++]
                        setHeader(header)
                        if (Protocol.positive(ask(cmd, "extended_discovery", 8000), cmd)) extraLive.add(header to cmd)
                    }
                }
                setHeader("7DF")
            }
            repeat(minOf(12, standard.size)) {
                if (!active()) return
                ask(standard[standardCursor++ % standard.size], "live_standard")
            }
            if (enhanced.isNotEmpty()) {
                setHeader("7E1")
                for (cmd in enhanced) { if (!active()) return; ask(cmd, "live_tcm") }
                setHeader("7DF")
            }
            if (can11 && extraLive.isNotEmpty()) {
                val (header, cmd) = extraLive[cycle % extraLive.size]
                setHeader(header); ask(cmd, "live_extended"); setHeader("7DF")
            }
            if (++cycle % 5 == 0) ask("ATRV", "adapter_voltage")
            // Retry negative TCM probes occasionally; they can differ between ignition/engine states.
            if (can11 && cycle % 10 == 0) {
                setHeader("7E1")
                for (cmd in TCM.filter { it !in enhanced }) {
                    if (Protocol.positive(ask(cmd, "tcm_retry"), cmd)) enhanced.add(cmd)
                }
                setHeader("7DF")
            }
            log.event("cycle", "number" to cycle, "standard_count" to standard.size,
                "tcm_count" to enhanced.size, "remaining_probes" to undeclared.size - cursor, "remaining_extended" to if (can11) probes.size-extraCursor else 0)
            status("Recording · cycle $cycle · ${standard.size} standard / ${enhanced.size} TCM reads · ${undeclared.size-cursor + if (can11) probes.size-extraCursor else 0} probes left")
            Thread.sleep(300)
        }
    }
    private fun setHeader(header: String) {
        val raw = ask("ATSH$header", "addressing")
        if (!raw.contains("OK")) throw IllegalStateException("Cannot select header $header: $raw")
    }
    companion object {
        // Read-only candidates, not confirmed on the 2018 1.7D. See docs/diagnostics.md.
        val TCM = listOf("2201A4", "2201A5", "2201A0", "21A0", "21A1")
    }
}
