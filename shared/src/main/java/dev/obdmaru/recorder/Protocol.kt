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
    /** Reassemble complete ISO-TP messages separately per ECU; reject missing/out-of-order frames. */
    fun payloads(raw: String): List<ByteArray> {
        data class Assembly(val total: Int, val bytes: MutableList<Byte>, var next: Int = 1)
        val pending = mutableMapOf<String, Assembly>()
        val result = mutableListOf<ByteArray>()
        raw.uppercase().split('\r', '\n').forEach { line ->
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.size < 2 || !Regex("[0-9A-F]{3}|[0-9A-F]{8}").matches(tokens[0])) return@forEach
            val b = tokens.drop(1).mapNotNull { if (it.length == 2) it.toIntOrNull(16) else null }
            if (b.isEmpty()) return@forEach
            val id = tokens[0]
            when (b[0] shr 4) {
                0 -> { val n = b[0] and 15; if (n > 0 && b.size >= n+1) result.add(b.drop(1).take(n).map { it.toByte() }.toByteArray()) }
                1 -> if (b.size >= 2) pending[id] = Assembly(((b[0] and 15) shl 8) or b[1], b.drop(2).map { it.toByte() }.toMutableList())
                2 -> {
                    val a = pending[id] ?: return@forEach
                    if (b[0] and 15 != a.next % 16) { pending.remove(id); return@forEach }
                    a.next++; a.bytes.addAll(b.drop(1).map { it.toByte() })
                    if (a.bytes.size >= a.total) { result.add(a.bytes.take(a.total).toByteArray()); pending.remove(id) }
                }
            }
        }
        return result
    }
    fun data(raw: String, command: String): ByteArray? {
        val prefix = ("%02X".format(command.take(2).toInt(16) + 0x40) + command.drop(2))
            .chunked(2).map { it.toInt(16).toByte() }
        return payloads(raw).firstOrNull { p -> p.size >= prefix.size && prefix.indices.all { p[it] == prefix[it] } }
            ?.drop(prefix.size)?.toByteArray()
    }
    fun positive(raw: String, command: String): Boolean {
        return data(raw, command) != null
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
