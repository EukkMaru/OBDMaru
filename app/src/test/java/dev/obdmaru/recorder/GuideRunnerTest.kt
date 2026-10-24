package dev.obdmaru.recorder

import org.junit.Assert.*
import org.junit.Test

class GuideRunnerTest {
    private val route=listOf(GuideStep("park",2,false,"Parked"),GuideStep("drive",2,true,"Drive"),GuideStep("brief",2,true,"Optional",60))
    @Test fun neverAnnouncesParkedInstructionsAfterDeparture() {
        val g=GuideRunner(route)
        assertEquals("drive",g.tick(0,20)?.id)
    }
    @Test fun waitsForFreshSpeedBeforeFirstInstruction() {
        val g=GuideRunner(route)
        assertNull(g.tick(0,null));assertTrue(g.paused)
        assertEquals("park",g.tick(100,0)?.id)
        assertNull(g.tick(200,0))
    }
    @Test fun movingTimersPauseAtStopsAndNoActionIsAssumed() {
        val g=GuideRunner(route)
        assertEquals("drive",g.tick(0,20)?.id)
        repeat(10) { g.tick((it+1)*1000L,0) }
        assertEquals("drive",g.current?.id);assertEquals(2,g.remainingSeconds())
        g.tick(11000,20);g.tick(12000,20)
        assertEquals("brief",g.current?.id)
    }
    @Test fun optionalFullInputDoesNotAnnounceAtHighSpeed() {
        val g=GuideRunner(listOf(GuideStep("brief",2,true,"Optional",60)))
        assertNull(g.tick(0,75));assertNull(g.tick(1000,65))
        assertEquals("brief",g.tick(2000,50)?.id)
        assertNull(g.tick(3000,70));assertEquals(2,g.remainingSeconds())
    }
    @Test fun finishingGuideDoesNotRepresentStoppingCapture() {
        val g=GuideRunner(listOf(GuideStep("drive",1,true,"Continue")))
        assertNotNull(g.tick(0,20));g.tick(1000,20)
        assertTrue(g.done);assertNull(g.tick(2000,20))
    }
    @Test fun departureIsAnnouncedBeforeMovingWithoutCountingParkedTime() {
        val g=GuideRunner(listOf(GuideStep("depart_60",5,true,"Begin when ready")))
        assertNotNull(g.tick(0,0))
        repeat(10) { assertNull(g.tick((it+1)*1000L,0)) }
        assertEquals(5,g.remainingSeconds())
        g.tick(11000,20);assertEquals(4,g.remainingSeconds())
    }
    @Test fun unavailableSpeedNeverTriggersStrongerInput() {
        val g=GuideRunner(listOf(GuideStep("brief",2,true,"Optional",60)))
        repeat(100) { assertNull(g.tick(it*1000L,null)) }
        assertFalse(g.done)
    }
    @Test fun highSpeedWindowIsSkippedRatherThanStallingForever() {
        val g=GuideRunner(listOf(GuideStep("brief",2,true,"Optional",60)))
        g.tick(0,75)
        repeat(75) { assertNull(g.tick((it+1)*1000L,75)) }
        assertTrue(g.done);assertEquals("brief",g.takeAutomaticSkip())
        assertNull(g.takeAutomaticSkip())
    }
    @Test fun routeHasAdjacentManualSequenceAndNoParkedThrottlePrompt() {
        assertEquals(listOf("manual_seventh","manual_sixth","manual_fifth","manual_back_sixth","manual_back_seventh"),GuidePlan.steps.filter { it.id.startsWith("manual_") && it.id!="manual_prepare" }.map { it.id })
        assertTrue(GuidePlan.steps.filter { !it.moving }.all { it.words.contains("accelerator",true) })
        assertEquals(60,GuidePlan.steps.first { it.id=="brief_full_gas" }.maxKph)
    }

    @Test fun departureExplicitlyReleasesParkingBrakeBeforePedalDeparture() {
        val setup=GuidePlan.steps.first{it.id=="departure_setup"}
        assertFalse(setup.moving)
        val brake=setup.words.indexOf("Press and hold the foot brake")
        val drive=setup.words.indexOf("select D")
        val release=setup.words.indexOf("Release the parking brake")
        assertTrue(brake>=0 && drive>brake && release>drive)
        assertTrue(setup.expectedAfter.contains("foot brake held"))
        assertTrue(GuidePlan.steps.indexOf(setup)<GuidePlan.steps.indexOfFirst{it.id=="depart_60"})
    }
    @Test fun cruiseAndManualTransitionsHaveSeparateWindowsAndFullParkingClosure() {
        val ids=GuidePlan.steps.map{it.id}
        val controls=listOf("parked_cruise_on","parked_cruise_off","departure_setup","cruise_ready","cruise_60","cruise_70","brake_cancel","cruise_resume","cruise_button_cancel","cruise_master_off","manual_prepare","return_to_d","normal_finish")
        assertEquals(controls,ids.filter{it in controls})
        assertTrue(GuidePlan.steps.all{it.expectedBefore.isNotBlank()&&it.expectedAfter.isNotBlank()})
        val closure=GuidePlan.COMPLETION
        assertTrue(closure.indexOf("complete stop")<closure.indexOf("select P"))
        assertTrue(closure.indexOf("apply the parking brake")<closure.indexOf("release the foot brake"))
    }
    @Test fun speechWaitCannotConsumeAWindowOrCreateAccumulatedTimeOnResume() {
        val g=GuideRunner(listOf(GuideStep("park",2,false,"Observe")))
        assertNotNull(g.tick(0,0))
        repeat(20){g.tick((it+1)*1000L,0,countTime=false)}
        assertEquals(2,g.remainingSeconds());assertFalse(g.done)
        g.tick(20100,0);assertFalse(g.done)
        g.tick(21100,0);assertFalse(g.done)
        g.tick(22100,0);assertTrue(g.done)
    }
}
