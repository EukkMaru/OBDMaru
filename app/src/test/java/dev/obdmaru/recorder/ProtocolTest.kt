package dev.obdmaru.recorder

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun unionsMultipleEcusAndPreservesContinuation() {
        val raw = "SEARCHING...\r7E8 06 41 00 08 18 00 01\r7E9 06 41 00 80 00 00 00\r>"
        assertEquals(setOf(1, 5, 12, 13, 32), Protocol.supported(raw, 1, 0))
    }
    @Test fun ignoresEchoNegativeAndTruncatedResponses() {
        assertEquals(emptySet<Int>(), Protocol.supported("0100\r7E8 03 7F 01 12\r41 00 FF\r>", 1, 0))
    }
    @Test fun handlesHeaderlessAndHigherPages() {
        assertEquals(setOf(33, 64), Protocol.supported("41 20 80 00 00 01\r>", 1, 32))
    }
    @Test fun distinguishesFailures() {
        assertEquals("no_data", Protocol.outcome("NO DATA\r>"))
        assertEquals("negative_response", Protocol.outcome("7E9 03 7F 22 31\r>"))
        assertEquals("unsupported_command", Protocol.outcome("?\r>"))
        assertEquals("empty", Protocol.outcome("\r>"))
    }
    @Test fun reassemblesMultiframeAndRejectsMissingOrWrongSequence() {
        val first="7E9 10 0A 62 01 A4 01 02 03\r"
        val good=first+"7E9 21 04 05 06 07 AA AA AA\r>"
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7),Protocol.data(good,"2201A4"))
        assertNull(Protocol.data(first+">","2201A4"))
        assertNull(Protocol.data(first+"7E9 22 04 05 06 07 AA AA AA\r>","2201A4"))
    }
    @Test fun ignoresNegativeAndOtherEcuServiceMatches() {
        assertNull(Protocol.data("7E9 03 7F 22 31\r>","2201A4"))
        assertNull(Protocol.data("7E8 04 41 0C 0C 80\r>","010D"))
    }
    @Test fun enhancedPositiveDoesNotAcceptRejection() {
        assertTrue(Protocol.positive("7E9 06 62 01 A4 00 00 00\r>", "2201A4"))
        assertFalse(Protocol.positive("7E9 03 7F 22 31\r>", "2201A4"))
    }
}
