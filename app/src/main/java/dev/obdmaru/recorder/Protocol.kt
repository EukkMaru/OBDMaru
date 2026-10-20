package dev.obdmaru.recorder

object Protocol {
    // Spaces are explicitly enabled on the adapter. Headers/ISO-TP PCI remain in raw logs.
    fun supported(raw: String, mode: Int, base: Int): Set<Int> {
        val result = sortedSetOf<Int>()
        raw.uppercase().split('\r', '\n').forEach { line ->
            val tokens = line.trim().split(Regex("\\s+")).mapNotNull {
                if (it.length == 2) it.toIntOrNull(16) else null
            }
            for (i in 0 until (tokens.size - 5).coerceAtLeast(0)) {
                if (tokens[i] == mode + 0x40 && tokens[i+1] == base) {
                    for (bit in 0..31) if (tokens[i+2+bit/8] and (0x80 shr (bit%8)) != 0)
                        result.add(base + bit + 1)
                }
            }
        }
        return result
    }
    fun positive(raw: String, command: String): Boolean {
        val prefix = "%02X".format(command.take(2).toInt(16) + 0x40) + command.drop(2)
        return raw.uppercase().split('\r', '\n').any { line ->
            line.replace(" ", "").contains(prefix)
        }
    }
    fun outcome(raw: String): String = when {
        "NO DATA" in raw -> "no_data"
        "UNABLE TO CONNECT" in raw -> "unable_to_connect"
        "CAN ERROR" in raw || "BUS ERROR" in raw || "BUS INIT: ERROR" in raw -> "bus_error"
        "BUFFER FULL" in raw -> "buffer_full"
        "STOPPED" in raw -> "stopped"
        "?" in raw -> "unsupported_command"
        Regex("7F\\s*(21|22|09|01)\\s*[0-9A-F]{2}").containsMatchIn(raw) -> "negative_response"
        raw.replace(">", "").isBlank() -> "empty"
        else -> "response"
    }
}
