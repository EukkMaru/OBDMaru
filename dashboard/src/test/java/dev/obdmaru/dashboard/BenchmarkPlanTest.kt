package dev.obdmaru.dashboard

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class BenchmarkPlanTest {
    private val speed=BenchmarkPlan.Query("7E0","010D",1)
    private val tcm=BenchmarkPlan.Query("7E1","2201A4",34)
    private class Rig {
        var time=0L;var active=true;var recover=true;var recoveries=0
        var headers=true;var spaces=false;var echo=false;var speed=0
        var fault:(String,String)->String?={_,_->null}
        val commands=mutableListOf<Pair<String,String>>()
        val events=mutableListOf<Pair<String,Map<String,Any?>>>()
        val progress=mutableListOf<String>()
        val link=object:DashboardLink {
            override fun query(command:String,phase:String,timeoutMs:Long):String {
                time+=5_000_000;commands+=command to phase
                fault(command,phase)?.let{return it}
                if(command.startsWith("AT")) {
                    when(command){"ATH0"->headers=false;"ATH1"->headers=true;"ATS0"->spaces=false;"ATS1"->spaces=true;"ATE0"->echo=false;"ATE1"->echo=true}
                    return when(command){"ATZ","ATI"->"ELM327 TEST\r>";"ATRV"->"13.8V\r>";"ATDP"->"ISO15765-4\r>";"ATPPS"->"00:FF F\r>";else->"OK\r>"}
                }
                val all=mapOf("010D" to listOf(0x41,0x0D,speed),"010C" to listOf(0x41,0x0C,0x0C,0x80),
                    "0149" to listOf(0x41,0x49,38),"0105" to listOf(0x41,0x05,127),
                    "010C0D" to listOf(0x41,0x0C,0x0C,0x80,0x0D,speed),
                    "010C0D4905" to listOf(0x41,0x0C,0x0C,0x80,0x0D,speed,0x49,38,0x05,127),
                    "2201A4" to listOf(0x62,1,0xA4)+List(34){0},"2201A5" to listOf(0x62,1,0xA5)+List(26){0},
                    "22C101" to listOf(0x62,0xC1,1)+List(39){0},"22ED06" to listOf(0x62,0xED,6)+List(34){0},
                    "22BC08" to listOf(0x62,0xBC,8)+List(5){0})
                val key=all.keys.sortedByDescending{it.length}.firstOrNull{command==it || command.length==it.length+1 && command.startsWith(it)}?:error(command)
                val data=all.getValue(key)
                val raw=if(!headers)data.joinToString(if(spaces)" "else ""){"%02X".format(it)}else {
                    val frames=if(data.size<=7)listOf(listOf(data.size)+data)else listOf(listOf(0x10,data.size)+data.take(6))+
                        data.drop(6).chunked(7).mapIndexed{i,b->listOf(0x21+i)+b}
                    frames.joinToString("\r"){"7E8"+(if(spaces)" "else "")+it.joinToString(if(spaces)" "else ""){v->"%02X".format(v)}}
                }
                return (if(echo)command+"\r"else "")+raw+"\r>"
            }
            override fun recover():Boolean {recoveries++;return recover}
            override fun close(){}
        }
        val plan=BenchmarkPlan(link,{time},{time+=it*1_000_000},{active},{t,f->events+=t to f},{progress+=it})
        fun samples()=events.filter{it.first=="benchmark_sample"}.map{it.second}
    }
    @Test fun entirePlanRunsWithCompleteRepliesAndSafeCommands() {
        val r=Rig();r.plan.run()
        assertEquals(73,BenchmarkPlan.phases().size)
        assertTrue(r.events.any{it.first=="benchmark_plan_end"})
        assertEquals(BenchmarkPlan.phases().size,r.events.count{it.first=="benchmark_phase_end"})
        assertTrue(r.samples().all{it["valid"]==true})
        assertTrue(r.commands.any{it.first=="2201A46"})
        assertTrue(r.commands.any{it.first=="22C1017"})
        assertFalse(r.commands.any{it.first.startsWith("ATBRD") || (it.first.startsWith("ATPP") && it.first!="ATPPS") || it.first.startsWith("ATFC")})
        assertTrue(r.commands.filter{!it.first.startsWith("AT")}.all{it.first.startsWith("01") || it.first.startsWith("22")})
    }
    @Test fun movingCarNeverStartsMeasurement() {
        val r=Rig();r.speed=1
        assertThrows(IOException::class.java){r.plan.run(listOf(BenchmarkPlan.Phase("x","x",listOf(speed))))}
        assertTrue(r.samples().isEmpty())
    }
    @Test fun MissingSpeedDoesNotPretendCarIsParked() {
        val r=Rig();r.fault={c,_->if(c=="010D")"NO DATA\r>"else null}
        assertThrows(IOException::class.java){r.plan.run(emptyList())}
        assertTrue(r.samples().isEmpty())
    }
    @Test fun functionalSpeedGuardFallbackStillRuns() {
        val r=Rig();var physical=true
        r.fault={c,_->if(c.startsWith("ATSH"))physical=c=="ATSH7E0";if(c=="010D" && physical)"NO DATA\r>"else null}
        r.plan.run(emptyList())
        assertTrue(r.commands.any{it.first=="ATSH7DF"})
        assertTrue(r.events.any{it.first=="benchmark_plan_end"})
    }
    @Test fun unsupportedSettingIsRecordedAndBaselineRestored() {
        val r=Rig();r.fault={c,_->if(c=="ATL1")"?\r>"else null}
        r.plan.run(listOf(BenchmarkPlan.Phase("unsupported","x",listOf(tcm),listOf("ATL1")),BenchmarkPlan.Phase("next","x",listOf(tcm))))
        assertTrue(r.events.any{it.first=="benchmark_phase_skip" && it.second["id"]=="unsupported"})
        assertTrue(r.samples().all{it["id"]=="next" && it["valid"]==true})
        assertFalse(r.headers.not())
    }
    @Test fun invalidRepliesAreLoggedThenPhaseStopsAfterThree() {
        val r=Rig();r.fault={c,p->if(p=="bench:bad" && !c.startsWith("AT"))"7E8 03 7F 22 31\r>"else null}
        r.plan.run(listOf(BenchmarkPlan.Phase("bad","bad",listOf(tcm)),BenchmarkPlan.Phase("next","ok",listOf(tcm))))
        val bad=r.samples().filter{it["id"]=="bad"}
        assertEquals(3,bad.size);assertTrue(bad.all{it["valid"]==false && it["wire_command"]=="2201A4"})
        assertTrue(r.samples().any{it["id"]=="next" && it["valid"]==true})
    }
    @Test fun timeoutRequiresRecoveryAndAbortsOnlyThatPhase() {
        val r=Rig();r.fault={_,p->if(p=="bench:bad")throw IOException("timeout") else null}
        r.plan.run(listOf(BenchmarkPlan.Phase("bad","bad",listOf(tcm)),BenchmarkPlan.Phase("next","ok",listOf(tcm))))
        assertEquals(1,r.recoveries)
        assertTrue(r.events.any{it.first=="benchmark_phase_abort"})
        assertTrue(r.samples().any{it["id"]=="next"})
    }
    @Test fun lostBoundaryStopsWholePlan() {
        val r=Rig();r.recover=false;r.fault={_,p->if(p=="bench:bad")throw IOException("timeout") else null}
        assertThrows(IOException::class.java){r.plan.run(listOf(BenchmarkPlan.Phase("bad","bad",listOf(tcm))))}
        assertFalse(r.events.any{it.first=="benchmark_plan_end"})
    }
    @Test fun cancellationSendsNoFurtherCommandsOrRecovery() {
        val r=Rig();r.fault={_,p->if(p=="bench:x"){r.active=false;throw IOException("closed")};null}
        assertThrows(InterruptedException::class.java){r.plan.run(listOf(BenchmarkPlan.Phase("x","x",listOf(tcm))))}
        assertEquals(0,r.recoveries)
        assertEquals("bench:x",r.commands.last().second)
    }
    @Test fun truncatedHintIsNotCountedAndDisablesHint() {
        val r=Rig();r.fault={c,p->if(p=="bench:x" && c=="2201A46")"7E8 10 25 62 01 A4 00 00 00\r>"else null}
        r.plan.run(listOf(BenchmarkPlan.Phase("x","x",listOf(tcm),hints=true,repeat=8)))
        val sample=r.samples()
        assertEquals(1,sample.count{it["valid"]==false})
        assertEquals("2201A4",sample.last()["wire_command"])
    }
    @Test fun headerlessValidationCannotMistakeEchoOrNegativeForData() {
        assertTrue(speed.valid("010D\r41 0D 00\r>",false))
        assertFalse(speed.valid("010D\r7F0131\r>",false))
        assertFalse(speed.valid("410D\r>",false))
        assertFalse(tcm.valid("6201A4\r>",false))
        assertFalse(BenchmarkPlan.Query(null,"ATRV").valid("OK\r>",true))
        assertTrue(BenchmarkPlan.Query(null,"ATRV").valid("ATRV\r13.8V\r>",true))
        assertFalse(BenchmarkPlan.Query(null,"ATPPS").valid("?\r>",true))
    }
    @Test fun batchValidationRequiresEveryPidInOneReply() {
        val batch=BenchmarkPlan.Query("7E0","010C0D",pids=mapOf(0x0C to 2,0x0D to 1))
        assertTrue(batch.valid("7E8 06 41 0D 00 0C 0C 80\r>",true))
        assertFalse(batch.valid("7E8 04 41 0C 0C 80\r7E9 03 41 0D 00\r>",true))
    }
}
