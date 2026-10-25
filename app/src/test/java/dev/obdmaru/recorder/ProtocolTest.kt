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
    @Test fun compactCanFramesSupportElevenAndTwentyNineBitIds() {
        assertArrayEquals(byteArrayOf(50),Protocol.data("7E803410D32\r>","010D"))
        assertArrayEquals(byteArrayOf(50),Protocol.data("18DAF11003410D32\r>","010D"))
        assertEquals(setOf(1,5,12,13,32),Protocol.supported("7E806410008180001\r7E906410080000000\r>",1,0))
        assertTrue(Protocol.frames("NO DATA\r010D\r>\rFFF03410D32").isEmpty())
    }
    @Test fun compactMultiframeStillRequiresCompleteOrderedPerEcuPayloads() {
        val first="7E9100A6201A4010203\r"
        val last="7E92104050607AAAAAA\r>"
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7),Protocol.data(first+last,"2201A4"))
        assertNull(Protocol.data(first+last.replace("7E9","7E8"),"2201A4"))
        assertNull(Protocol.data(first+last.replace("7E921","7E922"),"2201A4"))
    }
    @Test fun multiPidRequiresAllFieldsInOnePayloadAndAcceptsReorderedFields() {
        val lengths=mapOf(0x0C to 2,0x0D to 1)
        val p=Protocol.mode01Values("7E806410D320C1AF8\r>",lengths)!!
        assertArrayEquals(byteArrayOf(0x1A,0xF8.toByte()),p[0x0C]);assertArrayEquals(byteArrayOf(50),p[0x0D])
        for(raw in listOf("7E804410C1AF8", "7E804410C1AF8\r7E903410D32", "7E804410C1AF8\r7E803410D32", "7E806410D320D320C", "7E805410C1AF80D"))
            assertNull(raw,Protocol.mode01Values(raw+"\r>",lengths))
    }

}
