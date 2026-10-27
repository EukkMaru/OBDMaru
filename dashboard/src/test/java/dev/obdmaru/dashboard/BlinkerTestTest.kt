package dev.obdmaru.dashboard

import dev.obdmaru.recorder.CanMonitor
import dev.obdmaru.recorder.CanMonitorDecoder
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class BlinkerTestTest {
    @Test fun splitSerialChunksAndCrLfPreserveAllEightBytes() {
        val frames=mutableListOf<ByteArray>();val d=CanMonitorDecoder{frames.add(it)}
        d.feed("ATMA\r\n541 8 00 01 1");d.feed("8 03 06 06 FE C0\r\n54180000000000000000\r")
        assertEquals(2,frames.size);assertArrayEquals(byteArrayOf(0,1,24,3,6,6,-2,-64),frames[0]);assertFalse(d.prompted)
    }
    @Test fun candidateUnsignedBitPositionsAndEnumsRemainRaw() {
        val c=BlinkerCandidate.decode(byteArrayOf(0,0,-1,0,-1,-1,0,-1))
        assertEquals(BlinkerCandidate(3,3,1,1,3),c)
        assertEquals(BlinkerCandidate(0,0,0,0,0),BlinkerCandidate.decode(ByteArray(8)))
    }
    @Test fun individualFieldsDoNotOverlap() {
        for((index,mask,expected) in listOf(Triple(2,8,BlinkerCandidate(1,0,0,0,0)),
            Triple(7,64,BlinkerCandidate(0,1,0,0,0)),Triple(5,4,BlinkerCandidate(0,0,1,0,0)),
            Triple(5,2,BlinkerCandidate(0,0,0,1,0)),Triple(4,2,BlinkerCandidate(0,0,0,0,1)))) {
            assertEquals(expected,BlinkerCandidate.decode(ByteArray(8).apply{this[index]=mask.toByte()}))
        }
    }
    @Test fun rejectsWrongIdDlcTruncationAndAdapterErrors() {
        for(line in listOf("54280000000000000000\r","54170000000000000000\r","54180000\r", "?\r>","BUFFER FULL\r", "RX ERROR\r")) {
            assertThrows(IOException::class.java){CanMonitorDecoder{fail("Invalid frame emitted")}.feed(line)}
        }
    }
    @Test fun lineMemoryIsBounded() {assertThrows(IOException::class.java){CanMonitorDecoder{}.feed("A".repeat(129))}}
    @Test fun earlyPromptAbortsAndStops() {
        val writes=mutableListOf<String>();var stopped=false
        assertThrows(IOException::class.java){CanMonitor.run({0},{true},{">"},{writes.add(it)},{stopped=true;true},{},{})}
        assertEquals(listOf("ATMA\r"),writes);assertTrue(stopped)
    }
    @Test fun emptyBusTimesOutAndStillStops() {
        var t=0L;var stopped=false;var count=0
        val result=CanMonitor.run({t},{true},{t+=100;null},{},{stopped=true;true},{},{count++},500)
        assertEquals("time_limit",result);assertEquals(0,count);assertTrue(stopped);assertEquals(500,t)
    }
    @Test fun userFinishStopsAtBoundary() {
        var active=true;var stopped=false
        val result=CanMonitor.run({0},{active},{null},{},{stopped=true;true},{active=false},{})
        assertEquals("user_finished",result);assertTrue(stopped)
    }
    @Test fun readFailureStillAborts() {
        var stopped=false
        assertThrows(IOException::class.java){CanMonitor.run({0},{true},{throw IOException("socket")},{},{stopped=true;true},{},{})}
        assertTrue(stopped)
    }
    @Test fun cancellationStillAborts() {
        var stopped=false
        assertThrows(InterruptedException::class.java){CanMonitor.run({0},{true},{throw InterruptedException()},{},{stopped=true;true},{},{})}
        assertTrue(stopped)
    }
    @Test fun cannotClaimSuccessWithoutStopBoundary() {
        assertThrows(IOException::class.java){CanMonitor.run({0},{false},{null},{},{false},{},{})}
    }
    @Test fun failedCleanupRetainsOriginalAdapterError() {
        val error=assertThrows(IOException::class.java){CanMonitor.run({0},{true},{"BUFFER FULL\r"},{},{false},{},{})}
        assertTrue(error.message!!.contains("BUFFERFULL"));assertEquals(1,error.suppressed.size)
    }
    @Test fun ignoredFilterAbortsBeforeWrongFrameCanReachCandidate() {
        var stopped=false
        assertThrows(IOException::class.java){CanMonitor.run({0},{true},{"12380000000000000000\r"},{},{stopped=true;true},{},{fail()})}
        assertTrue(stopped)
    }
    private class Fake: BlinkerLink {
        val commands=mutableListOf<String>();var reject="";var fail="";var monitored=false;var streamFail=false
        var pps="?\r>";var extraReject="";var specialCommand="";var specialReply=""
        var stages:()->Unit={}
        override fun query(command:String):String {
            commands.add(command)
            if(command==fail)throw IOException("lost prompt")
            return if(command==specialCommand)specialReply else if(command=="ATPPS")pps else if(command==reject || command==extraReject)"?\r>"else if(command=="ATZ")"ELM327 v1.5\r>"else "OK\r>"
        }
        override fun monitor(active:()->Boolean,tick:()->Unit,frame:(ByteArray)->Unit):String {
            monitored=true;if(streamFail)throw IOException("monitor failed")
            tick();frame(ByteArray(8));stages();tick();return "user_finished"
        }
    }
    private fun plan(f:Fake,stage:()->Int={0},events:MutableList<Pair<String,Map<String,Any?>>> = mutableListOf(),ready:MutableList<Boolean> = mutableListOf())=
        BlinkerTest(f,{1000},stage,{type,fields->events.add(type to fields)},{},{ready.add(it)})
    @Test fun acceptedSetupMonitorsAndResetsBeforeRelease() {
        val f=Fake();val ready=mutableListOf<Boolean>();assertTrue(plan(f,ready=ready).run().contains("1 frames"))
        assertEquals(listOf("ATZ","ATE0","ATL0","ATH1","ATS0","ATSP6","ATCAF0","ATD1","ATCSM1","ATCRA541","ATZ"),f.commands)
        assertEquals(listOf(true,false),ready)
        assertFalse(f.commands.any{it=="ATCSM0" || it=="ATCFC0"})
    }
    @Test fun unsupportedRequiredSettingsNeverFallBackToUnfilteredOrUnestablishedSilentMonitoring() {
        for(command in listOf("ATCSM1","ATCF541","ATD1")) {
            val f=Fake().apply{reject=command}
            if(command=="ATCF541")f.extraReject="ATCRA541"
            assertThrows(IOException::class.java){plan(f).run()};assertFalse(f.monitored);assertEquals("ATZ",f.commands.last())
        }
    }
    @Test fun capturedFactoryDefaultTableAllowsUnsupportedSilentToggle() {
        val f=Fake().apply{reject="ATCSM1";pps="20:FF F  21:FF F  22:FF F\r>"}
        val events=mutableListOf<Pair<String,Map<String,Any?>>>()
        plan(f,events=events).run();assertTrue(f.monitored)
        assertEquals(listOf("ATCSM1","ATPPS","ATCRA541"),f.commands.drop(8).take(3))
        assertEquals("pp21_reported_reset_default",events.single{it.first=="blinker_test_start"}.second["silent_basis"])
        assertFalse(events.single{it.first=="blinker_silent_setup"}.second["hardware_verified"] as Boolean)
        assertFalse(f.commands.any{it.startsWith("ATPP") && it!="ATPPS" || it=="ATCSM0"})
    }
    @Test fun pp21EffectiveDefaultFollowsEnabledFlagWithoutTrustingVersionString() {
        for(table in listOf("21:FF F\r>","21:FF N\r>","21:00 F\r>","ATPPS\r21:FF F\r>"))assertTrue(BlinkerTest.silentDefault(table))
        for(table in listOf("21:00 N\r>","21:01 N\r>","?\r>","21:FF F", "21:FF F\r21:00 N\r>","21:FF F\rCAN ERROR\r>","20:FF F\r>"))assertFalse(table,BlinkerTest.silentDefault(table))
    }
    @Test fun unsafeOrMissingDefaultNeverStartsMonitor() {
        for(pps in listOf("?\r>","21:00 N\r>","21:FF F\rERROR\r>","21:FF F")) {
            val f=Fake().apply{reject="ATCSM1";this.pps=pps}
            assertThrows(IOException::class.java){plan(f).run()};assertFalse(f.monitored)
        }
    }
    @Test fun fallbackQueryTimeoutStopsWithoutFurtherCommands() {
        val f=Fake().apply{reject="ATCSM1";fail="ATPPS"}
        assertThrows(IOException::class.java){plan(f).run()};assertFalse(f.monitored);assertEquals("ATPPS",f.commands.last())
    }
    @Test fun olderFilterCommandsStillRequireExactMask() {
        val f=Fake().apply{reject="ATCRA541"};plan(f).run();assertTrue(f.monitored)
        assertEquals(listOf("ATCRA541","ATCF541","ATCM7FF","ATZ"),f.commands.takeLast(4))
        val bad=Fake().apply{reject="ATCRA541";extraReject="ATCM7FF"}
        assertThrows(IOException::class.java){plan(bad).run()};assertFalse(bad.monitored)
    }
    @Test fun mixedSettingReplyIsNotAcceptedOrTreatedAsUnsupported() {
        val f=Fake().apply{specialCommand="ATCSM1";specialReply="OK\r?\r>"}
        assertThrows(IOException::class.java){plan(f).run()};assertFalse(f.monitored);assertFalse(f.commands.contains("ATPPS"))
    }
    @Test fun queryTimeoutDoesNotSendResetIntoUnknownBoundary() {
        val f=Fake().apply{fail="ATCRA541"}
        assertThrows(IOException::class.java){plan(f).run()};assertEquals("ATCRA541",f.commands.last());assertFalse(f.monitored)
    }
    @Test fun failedMonitorDoesNotSendDiagnosticsIntoStream() {
        val f=Fake().apply{streamFail=true};val ready=mutableListOf<Boolean>()
        assertThrows(IOException::class.java){plan(f,ready=ready).run()};assertEquals("ATCRA541",f.commands.last());assertEquals(listOf(true,false),ready)
    }
    @Test fun userStageMarkersAndRawFrameAreRetained() {
        var stage=0;val events=mutableListOf<Pair<String,Map<String,Any?>>>()
        val f=Fake().apply{stages={stage=1}}
        plan(f,{stage},events).run()
        assertEquals(listOf(0,1),events.filter{it.first=="blinker_stage"}.map{it.second["stage"]})
        assertEquals("0000000000000000",events.single{it.first=="blinker_frame"}.second["data_hex"])
    }
}
