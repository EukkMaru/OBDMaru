package dev.obdmaru.watcher
import org.junit.Assert.*
import org.junit.Test
class WatchModelTest {
    @Test fun rawBytesAreUnsignedAndBitsKeepOrder() {
        assertEquals(listOf(0,1,128,255), WatchModel.unsigned(byteArrayOf(0,1,-128,-1)))
        assertEquals(listOf(true,false,false,false,false,false,false,true), WatchModel.bits(129))
    }
    @Test fun pollingDoesNotCatchUpWithBurstRequests() {
        assertEquals(65L,WatchModel.delayMs(1000,1035,10))
        assertEquals(165L,WatchModel.delayMs(1000,1035,5))
        assertEquals(0L,WatchModel.delayMs(1000,1400,10))
    }
    @Test fun catalogMatchesStableUnknownIndices() {
        assertEquals(20, WatchCatalog.blocks.size)
        assertEquals(531, WatchCatalog.blocks.sumOf { it.length })
        assertEquals("2201A4",WatchCatalog.blocks[18].command)
        assertEquals("U19",WatchCatalog.blocks[18].index)
        assertEquals(20,WatchCatalog.blocks.map { it.index }.distinct().size)
    }
}
