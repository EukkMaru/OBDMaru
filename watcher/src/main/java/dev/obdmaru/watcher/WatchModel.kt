package dev.obdmaru.watcher

data class Block(val index: String, val header: String, val command: String, val length: Int) {
    override fun toString() = "$index · $header/$command · $length bytes"
}
object WatchCatalog {
    val blocks = listOf(
        Block("U01", "7A0", "22B009", 8),
        Block("U02", "7C6", "22B002", 7),
        Block("U03", "7D1", "22C101", 39),
        Block("U04", "7D4", "2101", 13),
        Block("U05", "7D6", "2106", 32),
        Block("U06", "7E0", "22E0F1", 40),
        Block("U07", "7E0", "22ED01", 34),
        Block("U08", "7E0", "22ED02", 39),
        Block("U09", "7E0", "22ED03", 64),
        Block("U10", "7E0", "22ED04", 33),
        Block("U11", "7E0", "22ED05", 33),
        Block("U12", "7E0", "22ED06", 34),
        Block("U13", "7E0", "22ED07", 33),
        Block("U14", "7E0", "22ED13", 1),
        Block("U15", "7E0", "22ED1D", 2),
        Block("U16", "7E0", "22ED27", 39),
        Block("U17", "7E0", "22ED29", 12),
        Block("U18", "7E0", "22ED94", 8),
        Block("U19", "7E1", "2201A4", 34),
        Block("U20", "7E1", "2201A5", 26)
    )
}
object WatchModel {
    fun unsigned(bytes: ByteArray) = bytes.map { it.toInt() and 255 }
    fun delayMs(start: Long, end: Long, hz: Int): Long = (1000L / hz - (end - start)).coerceAtLeast(0)
    fun bits(value: Int) = (7 downTo 0).map { value and (1 shl it) != 0 }
}
