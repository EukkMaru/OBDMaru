package dev.obdmaru.scanner

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ScanRunnerTest {
    private val runner=ScanRunner(ScanDecoder(DtcCatalog.load("".reader())))
    @Test fun finiteScanKeepsUnavailableQueriesAndReachesEnd() {
        val sent=mutableListOf<String>();val results=mutableListOf<ScanReply>()
        runner.run({q->sent.add(q.command);if(q.command=="03")"7E8 02 43 00\r>"else"NO DATA\r>"},{true},{fail("Unexpected recovery");false},results::add)
        assertEquals(ScanPlan.queries.map{it.command},sent)
        assertEquals(15,results.size)
        assertEquals(1,results.count{it.findings.any{f->f.status=="Read"}})
    }
    @Test fun transportFailureStopsBeforeAnotherQueryAndKeepsEarlierEvidence() {
        val sent=mutableListOf<String>();val results=mutableListOf<ScanReply>()
        try {
            runner.run({q->sent.add(q.command);if(q.command=="07")throw IOException("lost prompt");"NO DATA\r>"},{true},{true},results::add)
            fail("Loss of boundary was ignored")
        }catch(_:IOException){}
        assertEquals(listOf("0101","03","07"),sent)
        assertEquals(2,results.size)
    }
    @Test fun failedRecoveryStopsScanWithoutDiscardingIncompleteReply() {
        var queries=0;val results=mutableListOf<ScanReply>();var recoveries=0
        try {
            runner.run({queries++;"7E8 10 0A 43 04 04 01 02 99\r>"},{true},{recoveries++;false},results::add)
            fail("Bad boundary ignored")
        }catch(_:IOException){}
        assertEquals(1,queries);assertEquals(1,recoveries);assertEquals(1,results.size)
        assertTrue(results.single().needsRecovery)
    }
    @Test fun successfulRecoveryHappensBeforeNextQuery() {
        val events=mutableListOf<String>()
        runner.run({q->events.add(q.command);if(q.command=="03")"7E8 10 0A 43 04 04 01 02 99\r>"else"NO DATA\r>"},{true},{events.add("recover");true},{})
        assertEquals(listOf("0101","03","recover","07"),events.take(4))
    }
    @Test fun stopAfterEmissionPreventsAnyFurtherRequest() {
        var active=true;var count=0
        try { runner.run({count++;"NO DATA\r>"},{active},{true},{active=false});fail("Cancellation ignored") }
        catch(_:InterruptedException){}
        assertEquals(1,count)
    }
    @Test fun pendingNegativeReplyRequiresRecovery() {
        var count=0
        runner.run({q->if(q.command=="03")"7E8 03 7F 03 78\r>"else"NO DATA\r>"},{true},{count++;true},{})
        assertEquals(1,count)
    }
}
