package dev.obdmaru.dashboard

import kotlin.math.sqrt

object Telemetry {
    fun u(bytes: ByteArray, i: Int): Int? = bytes.getOrNull(i)?.toInt()?.and(255)
    fun temperature(bytes: ByteArray, i: Int): Double? {
        val high = u(bytes,i) ?: return null; val low = u(bytes,i+1) ?: return null
        return ((high * 256 + low) * 2.0 - 40).takeIf { it in -40.0..300.0 }
    }
    fun transmission(bytes: ByteArray) = Transmission(
        u(bytes,8)?.let { it * 100.0 / 254 }, u(bytes,7)?.let { ((it-32)*100.0/213).coerceIn(0.0,100.0) },
        u(bytes,7),u(bytes,8),u(bytes,21),u(bytes,22),u(bytes,23)?.and(15),u(bytes,24)?.and(15),
        temperature(bytes,29),temperature(bytes,31),u(bytes,26)?.let { it and 1 != 0 })
    fun brake(bytes: ByteArray) = Brake(u(bytes,32)?.let { sqrt(it / 150.0) * 100.0 },u(bytes,32),u(bytes,33))
    fun fresh(at: Long, now: Long, maxAge: Long) = at > 0 && now >= at && now-at <= maxAge
    fun gear(t: Transmission?): String = when(t?.selector) {
        1 -> "P"; 2 -> "R"; 3 -> "N"
        else -> when(t?.gear) { in 1..7 -> t!!.gear.toString(); 14 -> "R"; else -> "—" }
    }
}
data class Transmission(val gas: Double?,val gasAlt: Double?,val rawGas7: Int?,val rawGas8: Int?,val selector: Int?,val mode: Int?,val gear: Int?,val nextGear: Int?,val clutch1: Double?,val clutch2: Double?,val brakeSwitch: Boolean?)
data class Brake(val percent: Double?,val raw32: Int?,val raw33: Int?)
data class Sample(val bytes: ByteArray, val at: Long, val hz: Double)
data class DashState(val samples: Map<String,Sample> = emptyMap(),val status: String = "Ready",val demo: Boolean = false)
