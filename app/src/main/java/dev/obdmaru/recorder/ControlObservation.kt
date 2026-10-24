package dev.obdmaru.recorder

/** Raw owner-observed control candidates. No cruise/parking-brake state is inferred. */
object ControlObservation {
    fun fields(header: String, command: String, data: ByteArray): Map<String,Int>? {
        fun u(i:Int)=data.getOrNull(i)?.toInt()?.and(255)
        fun selected(indices:List<Int>)=indices.mapNotNull { i->u(i)?.let{"b%02d".format(i) to it} }.toMap()
        return when("$header/$command") {
            "7E1/2201A4" -> selected(listOf(7,8,21,22,23,24,26)).toMutableMap().apply {
                u(26)?.let { put("brake_bit0_candidate",it and 1) }
                u(23)?.let { put("gear_low_nibble_candidate",it and 15) }
            }
            "7D1/22C101" -> selected(listOf(32,33))
            else -> null
        }
    }
}
