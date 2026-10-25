package dev.obdmaru.recorder

object Protocol {
    data class CanFrame(val header: String, val bytes: List<Int>)
    /** Headered CAN frames, with ATS1 spacing or compact ATS0 output. */
    fun frames(raw: String): List<CanFrame> = raw.uppercase().split('\r','\n').mapNotNull { line ->
        val hex=line.trim().replace(Regex("\\s+"), "")
        if(!Regex("[0-9A-F]+").matches(hex))return@mapNotNull null
        val n=if(hex.length%2==1)3 else 8
        if(hex.length<n+2 || hex.length>n+16)return@mapNotNull null
        val id=hex.take(n)
        if(id.toLong(16)>(if(n==3)0x7FFL else 0x1FFFFFFFL))return@mapNotNull null
        CanFrame(id,hex.drop(n).chunked(2).map{it.toInt(16)})
    }
    fun supported(raw: String, mode: Int, base: Int): Set<Int> {
        val result = sortedSetOf<Int>()
        val messages=payloads(raw).toMutableList()
        // Retain support for the recorder's older/headerless support-page replies.
        raw.uppercase().split('\r','\n').forEach { line ->
            val parts=line.trim().split(Regex("\\s+"))
            if(parts.size==6 && parts.all{Regex("[0-9A-F]{2}").matches(it)})
                messages.add(parts.map{it.toInt(16).toByte()}.toByteArray())
        }
        messages.forEach { p ->
            if(p.size>=6 && (p[0].toInt() and 255)==mode+0x40 && (p[1].toInt() and 255)==base) {
                    for (bit in 0..31) if ((p[2+bit/8].toInt() and 255) and (0x80 shr (bit%8)) != 0)
                        result.add(base + bit + 1)
            }
        }
        return result
    }
    /** Reassemble complete ISO-TP messages separately per ECU; reject missing/out-of-order frames. */
    fun payloads(raw: String): List<ByteArray> {
        data class Assembly(val total: Int, val bytes: MutableList<Byte>, var next: Int = 1)
        val pending = mutableMapOf<String, Assembly>()
        val result = mutableListOf<ByteArray>()
        frames(raw).forEach { frame ->
            val b = frame.bytes
            if (b.isEmpty()) return@forEach
            val id = frame.header
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
    /** Require every requested PID in one complete ECU payload; no cross-ECU merging. */
    fun mode01Values(raw: String, lengths: Map<Int,Int>): Map<Int,ByteArray>? {
        for(p in payloads(raw)) {
            if(p.isEmpty() || p[0]!=0x41.toByte())continue
            val result=linkedMapOf<Int,ByteArray>();var pos=1;var valid=true
            while(pos<p.size) {
                val pid=p[pos++].toInt() and 255
                val size=lengths[pid]
                if(size==null || pid in result || pos+size>p.size){valid=false;break}
                result[pid]=p.copyOfRange(pos,pos+size);pos+=size
            }
            if(valid && result.keys==lengths.keys)return result
        }
        return null
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
