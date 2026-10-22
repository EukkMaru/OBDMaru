package dev.obdmaru.recorder

import org.junit.Assert.*
import org.junit.Test

class PollSchedulerTest {
    @Test fun backgroundDiscoveryCannotBeStarvedByFastTraffic() {
        val s=PollScheduler(3)
        val fast=PollTask("7DF","010D",true,0)
        val background=PollTask("7E0","22ED03",false,15000,discovery=true)
        s.add(fast);s.add(background)
        repeat(3) { assertEquals(fast,s.next(1000));s.complete(fast,1000,true,false) }
        assertEquals(background,s.next(1000))
        s.complete(background,1000,true,false)
        assertEquals(0,s.remainingDiscovery())
    }
    @Test fun zerosAreRetriedSlowlyWithoutSlowingFastFields() {
        val s=PollScheduler(3)
        val zero=PollTask("7DF","0174",false,15000)
        val fast=PollTask("7DF","010D",true,500)
        s.complete(zero,100,true,true);assertEquals(120100,zero.nextMs)
        s.complete(fast,100,true,true);assertEquals(600,fast.nextMs)
        s.complete(zero,200000,true,false);assertEquals(215000,zero.nextMs)
    }
    @Test fun sameHeaderPreferenceDoesNotBypassMuchOlderWork() {
        val s=PollScheduler(3)
        val a=PollTask("7DF","010D",true,100)
        val b=PollTask("7E1","2201A4",true,100,nextMs=0)
        s.add(a);s.next(0);s.complete(a,1000,true,false);s.add(b)
        assertEquals(b,s.next(1500))
    }
    @Test fun broadSweepKeepsPrioritySignalsFrequentUnderRepresentativeAdapterLatency() {
        val s=PollScheduler(3)
        val speed=PollTask("7DF","010D",true,800)
        val clutch=PollTask("7E1","2201A4",true,600)
        s.add(speed);s.add(clutch)
        s.add(PollTask("7DF","0149",true,650));s.add(PollTask("7E0","22ED06",true,1500))
        repeat(77) { s.add(PollTask("7DF","probe$it",false,15000,discovery=true)) }
        var time=0L;var header="";val observed=mutableMapOf<String,MutableList<Long>>()
        while(time<90000) {
            val task=s.next(time)
            if(task==null) { time+=15;continue }
            if(task.header!=header) { time+=55;header=task.header }
            time+=115 // representative 100ms response plus 15ms turnaround
            observed.getOrPut(task.key) { mutableListOf() }.add(time)
            s.complete(task,time,true,false)
        }
        for(key in listOf(speed.key,clutch.key)) {
            val gaps=observed.getValue(key).zipWithNext { a,b -> b-a }
            assertTrue("$key max interval ${gaps.max()}",gaps.max()<2500)
        }
        assertEquals(0,s.remainingDiscovery())
    }
    @Test fun duplicateDiscoveryNeverReplacesFastTask() {
        val s=PollScheduler(3);val fast=PollTask("7DF","010D",true,500)
        s.add(fast);s.add(PollTask("7DF","010D",false,15000,discovery=true))
        assertEquals(1,s.size());assertTrue(s.next(0)!!.fast)
    }
}
