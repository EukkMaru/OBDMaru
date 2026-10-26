package dev.obdmaru.scanner

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ScanDecoderTest {
    private val catalog=DtcCatalog.load("P0401\tEGR flow insufficient\nP0299\tBoost below target\nP1701\tForeign maker definition must not leak\n".reader())
    private val decoder=ScanDecoder(catalog)
    private fun q(command: String)=ScanPlan.queries.first{it.command==command}
    private fun read(command: String, raw: String)=decoder.decode(q(command),raw)

    @Test fun storedCodesRetainEcuAndDoNotBorrowOtherManufacturerDefinitions() {
        val r=read("03","7E8 06 43 02 04 01 02 99\r7E9 04 43 01 17 01\r>")
        assertEquals(listOf("7E8","7E9"),r.findings.map{it.ecu})
        assertEquals(listOf("P0401","P0299"),r.findings[0].codes)
        assertEquals(listOf("P1701"),r.findings[1].codes)
        assertTrue(r.text.contains("EGR flow insufficient"))
        assertTrue(r.text.contains("Exact Hyundai TL definition not bundled"))
        assertFalse(r.text.contains("Foreign maker"))
    }
    @Test fun zeroCodesRequireExplicitPositiveReply() {
        val zero=read("0A","7E8 02 4A 00 00 00 00 00 00\r>")
        assertEquals("Read",zero.findings.single().status)
        assertTrue(zero.text.contains("explicitly reported 0"))
        for(raw in listOf("NO DATA\r>","CAN ERROR\r>",">","SEARCHING...\r>")) {
            val r=read("03",raw)
            assertTrue(r.findings.isEmpty()); assertFalse(r.text.contains("explicitly reported 0")); assertTrue(r.problems.isNotEmpty())
        }
    }
    @Test fun completeMultiFrameReplyDecodesAdvertisedCountOnly() {
        val r=read("03","7E8 10 0A 43 04 04 01 02 99\r7E8 21 20 02 01 33 00 00 00\r>")
        assertEquals(listOf("P0401","P0299","P2002","P0133"),r.findings.single().codes)
        assertTrue(r.problems.isEmpty())
    }
    @Test fun compactFramesAndEchoAreHandled() {
        val r=read("03","03\r7E80443010401\r>")
        assertEquals(listOf("P0401"),r.findings.single().codes)
    }
    @Test fun incompleteFramesNeverBecomeZeroCodesOrCarryIntoNextRequest() {
        val first=read("03","7E8 10 0A 43 04 04 01 02 99\r>")
        assertTrue(first.needsRecovery);assertTrue(first.findings.isEmpty())
        val next=read("07","7E8 21 20 02 01 33 00 00 00\r>")
        assertTrue(next.needsRecovery);assertTrue(next.findings.isEmpty())
    }
    @Test fun assembliesNeverMergeEcus() {
        val r=read("03","7E8 10 0A 43 04 04 01 02 99\r7E9 21 20 02 01 33 00 00 00\r>")
        assertTrue(r.findings.isEmpty());assertTrue(r.needsRecovery)
    }
    @Test fun validOtherEcuSurvivesAnIncompletePeer() {
        val r=read("03","7E8 10 0A 43 04 04 01 02 99\r7E9 02 43 00\r>")
        assertEquals("7E9",r.findings.single().ecu)
        assertTrue(r.needsRecovery)
    }
    @Test fun badFrameOrderIsRejected() {
        val r=read("03","7E8 10 0A 43 04 04 01 02 99\r7E8 22 20 02 01 33 00 00 00\r>")
        assertTrue(r.findings.isEmpty());assertTrue(r.needsRecovery)
    }
    @Test fun advertisedDtcCountAndPlaceholderValidation() {
        for(raw in listOf("7E8 04 43 02 04 01\r>","7E8 04 43 01 00 00\r>","7E8 04 43 00 04 01\r>")) {
            val r=read("03",raw)
            assertEquals("Unresolved",r.findings.single().status)
            assertTrue(r.findings.single().codes.isEmpty())
        }
    }
    @Test fun negativeOrWrongServiceDoesNotMeanNoCodes() {
        val negative=read("07","7E8 03 7F 07 12\r>")
        assertEquals("Unavailable",negative.findings.single().status)
        assertTrue(negative.text.contains("NRC 12"))
        val wrong=read("07","7E8 02 43 00\r>")
        assertEquals("Unavailable",wrong.findings.single().status)
    }
    @Test fun duplicateIdenticalReplyIsDeduplicatedButConflictsAreUnresolved() {
        assertEquals(1,read("03","7E8 04 43 01 04 01\r7E8 04 43 01 04 01\r>").findings.single().codes.size)
        assertEquals("Unresolved",read("03","7E8 04 43 01 04 01\r7E8 02 43 00\r>").findings.single().status)
    }
    @Test fun dieselReadinessUsesDieselMonitorLayout() {
        val r=read("0101","7E8 06 41 01 82 0F EB 40\r>")
        assertTrue(r.text.contains("light requested: ON"));assertTrue(r.text.contains("DTC count: 2"))
        assertTrue(r.text.contains("NOx/SCR: complete"));assertTrue(r.text.contains("Particulate filter: not complete"))
        assertFalse(r.text.contains("Evaporative"))
    }
    @Test fun sparkReadinessAndContinuousIncompleteBits() {
        val r=read("0101","7E8 06 41 01 00 17 04 04\r>")
        assertTrue(r.text.contains("Misfire: not complete"));assertTrue(r.text.contains("Fuel system: complete"))
        assertTrue(r.text.contains("Evaporative system: not complete"));assertTrue(r.text.contains("light requested: OFF"))
    }
    @Test fun freezeFrameCodeRpmAndCoolantDecodeWithFrameNumber() {
        assertEquals(listOf("P0401"),read("020200","7E8 05 42 02 00 04 01\r>").findings.single().codes)
        assertTrue(read("020C00","7E8 05 42 0C 00 1F 40\r>").text.contains("2000.00 RPM"))
        assertTrue(read("020500","7E8 04 42 05 00 7D\r>").text.contains("85 °C"))
        assertEquals("Unresolved",read("020C00","7E8 05 42 0C 01 1F 40\r>").findings.single().status)
    }
    @Test fun wrongPidAndTruncatedReadinessAreRejected() {
        assertEquals("Unresolved",read("020C00","7E8 04 42 0D 00 3C\r>").findings.single().status)
        assertEquals("Unresolved",read("0101","7E8 03 41 01 00\r>").findings.single().status)
    }
    @Test fun codeBitLayoutCoversAllFamilies() {
        assertEquals("P0133",ScanPlan.code(0x01,0x33));assertEquals("P1701",ScanPlan.code(0x17,1))
        assertEquals("C1234",ScanPlan.code(0x52,0x34));assertEquals("B2345",ScanPlan.code(0xA3,0x45));assertEquals("U0100",ScanPlan.code(0xC1,0))
    }
    @Test fun unresolvedManufacturerRangesCannotUseGenericFallback() {
        for(code in listOf("P1000","P3000","P33FF","B1000","C2000","U1000"))assertFalse(DtcCatalog.isGeneric(code))
        for(code in listOf("P0001","P2002","P3400","B0001","C0001","U0100"))assertTrue(DtcCatalog.isGeneric(code))
    }
    @Test fun planContainsOnlyBoundedReadsAndHasNoClearCommand() {
        assertEquals(15,ScanPlan.queries.size)
        assertTrue(ScanPlan.queries.all{it.command in listOf("03","07","0A","0101") || Regex("02[0-9A-F]{2}00").matches(it.command)})
        assertFalse(ScanPlan.setup.any{!it.startsWith("AT")})
        assertFalse(ScanPlan.queries.any{it.command.startsWith("04") || it.command.startsWith("14")})
    }
    @Test fun partialReportPreservesUnknownCoverageAndSyntheticLabel() {
        val r=ScanReport.render(listOf(read("03","NO DATA\r>")),true,"Stopped")
        assertTrue(r.contains("SYNTHETIC DEMO"));assertTrue(r.contains("Completed queries: 1/15"))
        assertTrue(r.contains("Stopped"));assertFalse(r.contains("explicitly reported 0"))
    }
    @Test fun bundledCatalogHasExpectedExamplesAndLicense() {
        val c=DtcCatalog.load(File("src/main/assets/dtc-generic.tsv").reader())
        assertTrue(c.description("P0401")!!.contains("Flow Insufficient"))
        assertTrue(c.description("P2002")!!.contains("Particulate"))
        assertNull(c.description("P1701"))
        assertTrue(File("src/main/assets/DTC-LICENSE.txt").readText().contains("MIT License"))
    }
}
