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
        val commands=mutableListOf<String>()
        val counts=mutableMapOf<String,Int>()
        val events=mutableListOf<String>()
        val poller=DashboardPolling(open={
            opens++
            object:DashboardLink {
                override fun query(command:String,phase:String,timeoutMs:Long):String {
                    time+=5;commands+=command
                    fault(command)?.let{return it}
                    if(command.startsWith("AT"))return if(command=="ATZ")"ELM327>" else "OK>"
                    counts[command]=(counts[command]?:0)+1
                    val data=when(command){"2201A4"->ByteArray(34).apply{this[8]=(counts[command]!!%255).toByte();this[30]=70;this[32]=75};"22C101"->ByteArray(39);else->byteArrayOf(50)}
                    val prefix=("%02X".format(command.take(2).toInt(16)+64)+command.drop(2)).chunked(2).map{it.toInt(16)}
                    val p=prefix+data.map{it.toInt() and 255}
                    val frames=if(p.size<=7)listOf(listOf(p.size)+p)else listOf(listOf(0x10,p.size)+p.take(6))+p.drop(6).chunked(7).mapIndexed{i,b->listOf(0x20+((i+1)%16))+b}
                    return frames.joinToString("\r"){"7E8 "+it.joinToString(" "){v->"%02X".format(v)}}+"\r>"
                }
                override fun recover():Boolean {recoveries++;return recover}
                override fun close(){closes++}
            }
        },now={time},sleep={time+=it},publish={state=it},event={type,_->events+=type})
        fun run(ms:Long){val end=time+ms;var guard=0;while(time<end){poller.step();check(++guard<10000)}}
    }
    @Test fun targetsTenTenThreeAndOneWithoutTemperatureExtraRequests(){
        val r=Rig();r.run(6000)
        assertEquals(1,r.opens)
        assertTrue("counts=${r.counts} state=${r.state} events=${r.events}",r.counts.getValue("2201A4") in 57..61)
        assertTrue(r.counts.getValue("22C101") in 57..61)
        assertTrue(r.counts.getValue("010D") in 17..19)
        assertTrue(r.state.samples.getValue("tcm").hz in 9.5..10.5)
        assertTrue(r.state.samples.getValue("clutch").hz in 0.9..1.1)
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
}
