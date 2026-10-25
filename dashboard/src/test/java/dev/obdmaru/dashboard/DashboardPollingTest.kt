package dev.obdmaru.dashboard

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DashboardPollingTest {
    private class Rig {
        var time=1000L
        var state=DashState()
        var opens=0;var closes=0;var recoveries=0
        var fault:(String)->String?={null}
        var recover=true
        var headerCost=5L
        var queryCost=5L
        var finalWait=0L
        var onEvent:(String,Map<String,Any?>)->Unit={_,_->}
        val commands=mutableListOf<String>()
        val counts=mutableMapOf<String,Int>()
        val events=mutableListOf<String>()
        val poller=DashboardPolling(open={
            opens++
            object:DashboardLink {
                override fun query(command:String,phase:String,timeoutMs:Long):String {
                    time+=if(command.startsWith("AT"))headerCost else queryCost
                    commands+=command
                    fault(command)?.let{return it}
                    if(command.startsWith("AT"))return if(command=="ATZ")"ELM327>" else "OK>"
                    val logical=command.take(if(command.startsWith("01"))4 else 6)
                    if(logical==command)time+=finalWait
                    counts[logical]=(counts[logical]?:0)+1
                    val data=when(logical){"2201A4"->ByteArray(34).apply{this[8]=(counts[logical]!!%255).toByte();this[30]=70;this[32]=75};"22C101"->ByteArray(39);"010C"->byteArrayOf(0x1A,0xF8.toByte());"0105"->byteArrayOf(0x7F);"22BC08"->ByteArray(5).apply{this[4]=6};else->byteArrayOf(50)}
                    val prefix=("%02X".format(logical.take(2).toInt(16)+64)+logical.drop(2)).chunked(2).map{it.toInt(16)}
                    val p=prefix+data.map{it.toInt() and 255}
                    val frames=if(p.size<=7)listOf(listOf(p.size)+p)else listOf(listOf(0x10,p.size)+p.take(6))+p.drop(6).chunked(7).mapIndexed{i,b->listOf(0x20+((i+1)%16))+b}
                    return frames.joinToString("\r"){"7E8 "+it.joinToString(" "){v->"%02X".format(v)}}+"\r>"
                }
                override fun recover():Boolean {recoveries++;return recover}
                override fun close(){closes++}
            }
        },now={time},sleep={time+=it},publish={state=it},event={type,fields->events+=type;onEvent(type,fields)})
        fun run(ms:Long){val end=time+ms;var guard=0;while(time<end){poller.step();check(++guard<10000)}}
    }
    @Test fun targetsTenTenThreeAndOneWithoutTemperatureExtraRequests(){
        val r=Rig();r.run(6000)
        assertEquals(1,r.opens)
        assertTrue("counts=${r.counts} state=${r.state} events=${r.events}",r.counts.getValue("2201A4") in 57..61)
        assertTrue(r.counts.getValue("22C101") in 57..61)
        assertTrue(r.counts.getValue("010D") in 17..19)
        assertTrue(r.counts.getValue("010C") in 28..31)
        assertTrue(r.counts.getValue("0105") in 5..7)
        assertTrue(r.counts.getValue("22BC08") in 9..13)
        assertTrue(r.state.samples.getValue("tcm").hz in 9.5..10.5)
        assertTrue(r.state.samples.getValue("clutch").hz in 0.9..1.1)
        assertTrue(r.state.samples.getValue("coolant").hz in 0.9..1.1)
        assertEquals(87.0,Telemetry.coolant(r.state.samples.getValue("coolant").bytes)!!,0.001)
        val previous=r.state.samples.getValue("clutch").at
        while(r.state.samples.getValue("clutch").at==previous)r.poller.step()
        val clutch=r.state.samples.getValue("clutch")
        r.run(150)
        assertEquals(clutch.at,r.state.samples.getValue("clutch").at)
        assertTrue(r.state.samples.getValue("tcm").at>clutch.at)
    }
    @Test fun rejectedBrakeDoesNotReconnectOrStarveOtherChannels(){
        val r=Rig();r.fault={if(it=="22C101")"7D9 03 7F 22 31\r>"else null};r.run(6000)
        assertEquals(1,r.opens);assertEquals(0,r.recoveries)
        assertFalse(r.state.samples.containsKey("brake"))
        assertTrue(r.counts.getValue("2201A4")>50);assertTrue(r.counts.getValue("010D")>15)
        assertTrue(r.commands.count{it=="22C101"}<=4)
    }
    @Test fun ecuTimeoutResyncsOnSameSocketAndResumesFailedChannel(){
        val r=Rig();var failed=false
        r.fault={if(it=="22C101"&&!failed){failed=true;r.time+=2500;throw IOException("No prompt")}else null}
        r.run(6500)
        assertEquals(1,r.opens);assertEquals(1,r.recoveries);assertEquals(0,r.closes)
        assertTrue(r.state.samples.keys.containsAll(listOf("tcm","brake","speed","clutch")))
        assertTrue(r.events.contains("dashboard_resynced"))
    }
    @Test fun brokenSocketReopensAndReappliesSetupWithoutResetLoop(){
        val r=Rig();var failed=false;r.recover=false
        r.fault={if(it=="22C101"&&!failed){failed=true;throw IOException("Bluetooth EOF")}else if(it=="ATPC")"?>"else null}
        r.run(6500)
        assertEquals(2,r.opens);assertEquals(1,r.closes)
        assertEquals(1,r.commands.count{it=="ATZ"});assertEquals(1,r.commands.count{it=="ATPC"})
        assertTrue(r.state.samples.containsKey("brake"));assertTrue(r.events.contains("dashboard_reconnect"))
    }
    @Test fun failedHeaderIsRecoveredBeforeAnyDiagnosticUsesWrongHeader(){
        val r=Rig();var rejected=false
        r.fault={if(it=="ATSH7D1"&&!rejected){rejected=true;"?>"}else null};r.run(3500)
        assertEquals(1,r.opens);assertEquals(1,r.recoveries)
        val firstRead=r.commands.indexOf("22C101")
        assertTrue(firstRead>0);assertEquals("ATSH7D1",r.commands[firstRead-1])
        assertTrue(r.state.samples.containsKey("brake"))
    }
    @Test fun setupFailureClosesFailedLinkAndRetriesInsteadOfEndingSession(){
        val r=Rig();var rejected=false
        r.fault={if(it=="ATE0"&&!rejected){rejected=true;"?>"}else null};r.run(5000)
        assertEquals(2,r.opens);assertEquals(1,r.closes);assertEquals(0,r.recoveries)
        assertTrue(r.state.samples.containsKey("speed"))
    }

    @Test fun learnsFullFrameHintsAndEliminatesFinalWaitWithoutTruncatingReads(){
        val r=Rig();r.finalWait=200;r.run(15000)
        assertTrue(r.commands.contains("2201A46"));assertTrue(r.commands.contains("22C1017"));assertTrue(r.commands.contains("010D1"))
        assertFalse(r.events.contains("dashboard_channel_failure"))
        assertTrue(r.state.samples.getValue("tcm").hz>8)
        assertTrue(r.state.samples.getValue("speed").hz>2.5)
    }
    @Test fun rejectedCountSyntaxFallsBackToPlainRequestsOnSameSocket(){
        val r=Rig();r.fault={if(!it.startsWith("AT") && it.length%2==1)"?>"else null};r.run(6000)
        assertEquals(1,r.opens);assertEquals(0,r.recoveries)
        assertTrue(r.events.contains("dashboard_reply_hint_disabled"))
        assertTrue(r.state.samples.keys.containsAll(listOf("tcm","brake","speed")))
        assertTrue(r.commands.takeLast(10).filterNot{it.startsWith("AT")}.all{it.length%2==0})
    }
    @Test fun slowLinkReservesSpeedAndReportsHeaderAndQueryCost(){
        val r=Rig();r.headerCost=45;r.queryCost=90
        val timings=mutableListOf<Map<String,Any?>>()
        r.onEvent={type,fields->if(type=="dashboard_poll_timing")timings+=fields}
        r.run(12000)
        assertTrue(r.state.samples.getValue("speed").hz>2)
        assertTrue(r.counts.getValue("2201A4")>5);assertTrue(r.counts.getValue("22C101")>5)
        assertTrue(timings.any{it["header_ms"]==45L && it["query_ms"]==90L && it["total_ms"]==135L})
    }
    @Test fun unsupportedPhysicalSpeedFallsBackToFunctionalAddress(){
        val r=Rig();var physical=true
        r.fault={if(it=="ATSH7DF"){physical=false;null}else if(physical&&it=="010D")"NO DATA\r>"else null}
        r.run(4000)
        assertEquals(1,r.opens);assertTrue(r.commands.contains("ATSH7DF"))
        assertTrue(r.events.contains("dashboard_speed_fallback"));assertTrue(r.state.samples.containsKey("speed"))
    }

    @Test fun timedOutPhysicalSpeedAlsoFallsBackAfterResync(){
        val r=Rig();var failed=false
        r.fault={if(it=="010D"&&!failed){failed=true;throw IOException("Physical ECU timed out")}else null}
        r.run(5000)
        assertEquals(1,r.opens);assertEquals(1,r.recoveries)
        assertTrue(r.commands.contains("ATSH7DF"));assertTrue(r.state.samples.containsKey("speed"))
    }

    @Test fun unsupportedIndicatorRequestBacksOffWithoutDisruptingMotionStreams(){
        val r=Rig();r.fault={if(it=="22BC08")"778 03 7F 22 31\r>"else null};r.run(12000)
        assertEquals(1,r.commands.count{it=="22BC08"});assertEquals(0,r.recoveries)
        assertFalse(r.state.samples.containsKey("indicators"));assertTrue(r.state.availability.getValue("indicators").startsWith("Unavailable"))
        assertTrue(r.counts.getValue("010C")>50);assertTrue(r.counts.getValue("2201A4")>100)
    }
    @Test fun rpmImmediatelyFollowsDueSpeedWithoutAnExtraHeaderChange(){
        val r=Rig();r.run(2000)
        val first=r.commands.indexOf("010D")
        assertEquals("010C",r.commands[first+1])
        assertEquals("0105",r.commands[first+2])
        assertTrue(r.state.samples.containsKey("rpm"));assertTrue(r.state.samples.containsKey("indicators"))
    }
    @Test fun coolantFailureRemovesItsSampleWithoutResettingOtherChannels(){
        val r=Rig();r.run(1500)
        assertTrue(r.state.samples.containsKey("coolant"))
        r.fault={if(it.startsWith("0105"))"NO DATA\r>"else null};r.run(4000)
        assertFalse(r.state.samples.containsKey("coolant"))
        assertTrue(r.state.availability.getValue("coolant").startsWith("Unavailable"))
        assertEquals(1,r.opens);assertEquals(0,r.recoveries)
        assertTrue(r.state.samples.keys.containsAll(listOf("tcm","brake","speed","rpm","clutch")))
        assertTrue(r.commands.count{it.startsWith("0105")}<=6)
    }
    @Test fun unsupportedPhysicalCoolantSharesFunctionalFallbackWithSpeedAndRpm(){
        val r=Rig();var physical=true
        r.fault={if(it=="ATSH7DF"){physical=false;null}else if(physical&&it.startsWith("0105"))"NO DATA\r>"else null}
        r.run(4000)
        assertTrue(r.commands.contains("ATSH7DF"));assertEquals(1,r.opens)
        assertTrue(r.events.contains("dashboard_engine_fallback"))
        assertTrue(r.state.samples.keys.containsAll(listOf("speed","rpm","coolant")))
    }
}
