package dev.obdmaru.recorder

/** Before/after states are instructions and hypotheses, never confirmations of driver actions. */
data class GuideStep(val id: String, val seconds: Int, val moving: Boolean, val words: String,
                     val maxKph: Int? = null, val expectedBefore: String = "", val expectedAfter: String = "")
object GuidePlan {
    const val VERSION = "2.0.1-continuous-controls"
    const val COMPLETION = "The guided sequence is complete. Recording continues. Keep normal D and cruise off until you reach a safe parking place. When ready, release the accelerator, brake normally to a complete stop, and keep the foot brake held. While stationary, select P, apply the parking brake and check that the car is secure. Only then release the foot brake. Leave the accelerator alone. Stop and save the recording while parked; no engine shutdown is required for this test."
    val steps = listOf(
        GuideStep("parked_baseline", 25, false, "Start only while safely parked on level ground, engine idling, selector in P and parking brake applied. Keep the accelerator released. If the cruise indicator is on, switch the cruise master off; otherwise leave it off. Once the car is secure, release the foot brake and leave both pedals alone for this baseline.", null, "Safely parked; engine idling", "P; parking brake applied; accelerator released; cruise master off; foot brake released"),
        GuideStep("parked_light_brake", 25, false, "Stay in P with the parking brake applied and cruise off. Keep the accelerator released. Press the foot brake lightly for two seconds, then release it fully. Repeat once, and finish with your foot off the brake.", null, "P; parking brake applied; accelerator released; cruise master off; foot brake released", "P; parking brake applied; accelerator released; cruise master off; foot brake released"),
        GuideStep("parked_firm_brake", 30, false, "Remain in P with the parking brake applied and cruise off. Leave the accelerator released. Press the foot brake firmly but comfortably for two seconds, then release. Make two light taps followed by one firmer press. Release the foot brake fully at the end.", null, "P; parking brake applied; accelerator released; cruise master off; foot brake released", "P; parking brake applied; accelerator released; cruise master off; foot brake released"),
        GuideStep("parked_cruise_on", 20, false, "Still parked in P, parking brake applied and accelerator and foot brake released, switch the cruise master on once if its indicator is off; if already on, leave it on. Do not press SET or RESUME. Observe whether the cruise indicator comes on. If the car will not enable it while parked, leave it alone; that is useful information too.", null, "P; parking brake applied; accelerator released; cruise master off; foot brake released", "P; parking brake applied; both pedals released; cruise on requested, not set"),
        GuideStep("parked_cruise_off", 20, false, "Keep P and the parking brake applied. Leave the accelerator and foot brake released. If the cruise indicator is on, switch the cruise master off once; if already off, leave it off. Do not change the selector yet.", null, "Parked cruise-on observation", "P; parking brake applied; accelerator released; cruise master off; foot brake released"),
        GuideStep("departure_setup", 25, false, "Prepare to depart only when safe. Keep the accelerator released and cruise off. Press and hold the foot brake. With the car stationary, select D. Release the parking brake using the normal control, and check that its warning indicator goes out. Keep holding the foot brake until the road is clear and the next departure instruction.", null, "P; parking brake applied; accelerator released; cruise master off; foot brake released", "D; parking brake released and indicator checked; cruise off; foot brake held; accelerator released"),
        GuideStep("depart_60", 75, true, "Before moving, confirm D, cruise off and parking brake fully released. If you missed the setup, remain stopped with the foot brake held and complete it first. When the road is clear, release the foot brake, then use light accelerator to depart smoothly toward sixty if suitable. Once moving, keep D and comfortable revs; do not force a gear or chase sixty.", null, "Departure setup completed, or already driving normally", "D; parking brake released; cruise off; driver controls speed"),
        GuideStep("cruise_ready", 20, true, "Keep D and the parking brake released. At a suitable steady legal pace near sixty, use the accelerator normally and keep your foot off the brake. If the cruise indicator is off, switch the cruise master on once; if already on, leave it on. Do not press SET or RESUME yet. This window is cruise on without engagement.", null, "D; parking brake released; cruise off", "D; parking brake released; cruise master on but not engaged; accelerator controls speed"),
        GuideStep("cruise_60", 55, true, "With D and cruise master on, if the road permits, settle near sixty using the accelerator. Press SET once at your actual speed. Once cruise is holding and the SET indicator is on, lift completely off the accelerator. Keep your foot off the brake. If it does not engage, continue normal driving rather than repeating button presses.", null, "D; parking brake released; cruise master on but not engaged; accelerator controls speed", "Cruise SET near 60 requested; pedals released if engaged"),
        GuideStep("cruise_70", 65, true, "Only if cruise is already engaged, keep both pedals released and raise the set speed gradually toward seventy using the cruise increase button. Let it settle and leave cruise engaged. Follow any lower speed limit instead. If cruise is not engaged, skip the button changes and continue driving normally.", null, "Cruise near 60 requested", "Cruise set-speed increase toward 70 requested; pedals released if engaged"),
        GuideStep("brake_cancel", 45, true, "Leave the cruise master on. At the next naturally needed slowdown, first keep the accelerator released, then brake gently to cancel cruise. Check the SET indicator goes out. Release the foot brake after the slowdown, then use the accelerator normally as needed. Do not press RESUME, SET or the cruise master button in this window.", null, "Cruise near 70 requested", "D; parking brake released; cruise master on but not engaged; brake released after slowdown; accelerator controls speed"),
        GuideStep("soft_gas", 40, true, "Continue in D with the parking brake released. If SET is still lit, press CANCEL once before this test. Leave the cruise master on, but do not SET or RESUME. With the foot brake released, apply light accelerator for a gentle acceleration, then release the accelerator and coast when appropriate. Resume ordinary pedal control afterward.", null, "D; parking brake released; cruise master on but not engaged", "D; parking brake released; cruise master on but not engaged; accelerator controls speed"),
        GuideStep("progressive_gas", 40, true, "Keep D, cruise not engaged, and the foot brake released. Smoothly increase accelerator from light to moderate for a few seconds, then ease back and release it fully to coast when appropriate. Return to ordinary pedal control. Leave the cruise buttons and selector alone.", null, "D; parking brake released; cruise master on but not engaged", "D; parking brake released; cruise master on but not engaged; accelerator controls speed"),
        GuideStep("brief_full_gas", 40, true, "Optional stronger input. Keep D and cruise not engaged, with the foot brake released. Only with clear road and ample speed headroom, smoothly press the accelerator fully for about one second, ease back toward moderate, then release it fully. Skip the action if unsuitable. Return to ordinary pedal control; do not sustain full throttle or chase a speed.", 60, "D; parking brake released; cruise master on but not engaged", "D; parking brake released; cruise master on but not engaged; accelerator controls speed"),
        GuideStep("light_brake", 35, true, "Keep D and cruise not engaged. First release the accelerator and coast. At a naturally needed slowdown, apply the foot brake lightly, then release it fully. Use the accelerator again only after the brake is released and when needed. Do not create an unnecessary stop.", null, "D; parking brake released; cruise master on but not engaged", "D; parking brake released; cruise master on but not engaged; brake released after slowdown; accelerator controls speed"),
        GuideStep("alternate_gas", 45, true, "Keep D, cruise not engaged and the foot brake released. When appropriate, build accelerator smoothly from light to moderate, ease back to light, then release it fully to coast. A brief firmer input is optional. Return to normal pedal control. Do not overlap brake and accelerator or change cruise settings.", 65, "D; parking brake released; cruise master on but not engaged", "D; parking brake released; cruise master on but not engaged; accelerator controls speed"),
        GuideStep("firmer_brake", 40, true, "Keep D and cruise not engaged. Release the accelerator first and coast. At a naturally needed slowdown, optionally brake more firmly than before, then release the foot brake fully. Return to normal accelerator control afterward. No emergency stop or immediate full acceleration is requested.", null, "D; parking brake released; cruise master on but not engaged", "D; parking brake released; cruise master on but not engaged; brake released after slowdown; accelerator controls speed"),
        GuideStep("cruise_resume", 45, true, "Optional resume check in D. Keep the cruise master on and the foot brake released. Only if the remembered set speed is known and suitable for the road, press RESUME once, then lift off the accelerator as cruise takes over. Let it settle and leave it engaged for the next prompt. Otherwise skip RESUME and drive normally.", null, "D; parking brake released; cruise master on but not engaged", "Cruise resumed if remembered speed suitable; pedals released if engaged"),
        GuideStep("cruise_button_cancel", 20, true, "Keep D and leave the cruise master on. Press the cruise CANCEL button once. Check SET goes out. Do not touch the brake just for this cancellation. After cruise releases, use the accelerator normally to maintain a suitable speed. Do not press SET or RESUME again.", null, "Cruise resume observation", "D; parking brake released; cruise master on but not engaged; accelerator controls speed"),
        GuideStep("cruise_master_off", 20, true, "Continue normal driving in D using the accelerator as needed, with the foot brake released except for normal traffic needs. If the cruise indicator is on, switch the cruise master off and check it goes out; if already off, leave it off. Leave cruise off for all remaining gear tests.", null, "D; parking brake released; cruise master on but not engaged", "D; parking brake released; cruise master off; accelerator controls speed"),
        GuideStep("manual_prepare", 75, true, "Keep D, parking brake released and cruise off. Settle at a steady legal pace, up to around ninety only if appropriate, leaving speed-limit margin. Let D choose seventh naturally. Do not accelerate just to reach seventh. Control speed with the accelerator and use the brake only for ordinary traffic needs.", null, "D; parking brake released; cruise off", "D; seventh if naturally appropriate; cruise off"),
        GuideStep("manual_seventh", 30, true, "Only if steady in seventh with comfortable revs, move the selector from D to manual mode without requesting a shift, and hold seventh. Keep cruise off and maintain speed with the accelerator. If seventh is unsuitable or unavailable, stay in D and ignore the following gear requests until told to return to D.", null, "D; seventh if suitable; cruise off", "manual mode; parking brake released; cruise master off; seventh if suitable, otherwise remain D"),
        GuideStep("manual_sixth", 25, true, "Only if you completed manual mode in seventh, request one adjacent downshift to sixth when speed and revs allow. Let it settle and maintain a comfortable legal pace with the accelerator. Keep cruise off. If not in that starting state, skip this request and continue normally.", null, "manual mode; parking brake released; cruise master off; seventh", "manual mode; parking brake released; cruise master off; sixth requested"),
        GuideStep("manual_fifth", 25, true, "Only if settled in manual sixth and revs allow, request one adjacent downshift to fifth. Let it settle and maintain a comfortable legal pace. Keep cruise off; do not force a refused shift. If the previous request was skipped or unsuitable, skip this one too.", null, "manual mode; parking brake released; cruise master off; sixth", "manual mode; parking brake released; cruise master off; fifth requested"),
        GuideStep("manual_back_sixth", 25, true, "If settled in manual fifth, request one adjacent upshift to sixth and let it settle. Keep cruise off and maintain a suitable speed with the accelerator. If not in fifth, skip this requested shift and drive normally rather than forcing the sequence.", null, "manual mode; parking brake released; cruise master off; fifth", "manual mode; parking brake released; cruise master off; sixth requested"),
        GuideStep("manual_back_seventh", 25, true, "If settled in manual sixth and the pace suits seventh, request one adjacent upshift to seventh and let it settle. Keep cruise off. If unsuitable, keep your current appropriate gear. Stay in your current selector mode until the next return-to-D prompt.", null, "manual mode; parking brake released; cruise master off; sixth", "manual mode; parking brake released; cruise master off; seventh requested if suitable"),
        GuideStep("return_to_d", 25, true, "Finish the manual section now. If in manual mode, move the selector back to normal D. If already in D, leave it there. Keep cruise master off and parking brake released. Use normal accelerator and brake control as traffic requires; no further gear requests are planned.", null, "Manual sequence completed or skipped", "D; parking brake released; cruise master off; normal pedal control"),
        GuideStep("normal_finish", 60, true, "Continue normal driving in D with cruise off and parking brake released. The guide will finish shortly, but recording continues. Do not pull over just to match the timer. At the end you will hear the full parking procedure; drive until a suitable safe parking place is available.", null, "D; parking brake released; cruise off", "D; cruise off; normal driving, awaiting safe parking opportunity")
    )
}

class GuideRunner(private val steps: List<GuideStep> = GuidePlan.steps) {
    var index = -1; private set
    private var lastMs = 0L
    private var accruedMs = 0L
    private var initialized = false
    private var announced = false
    private var waitingMs = 0L
    var automaticSkip: String? = null; private set
    fun takeAutomaticSkip(): String? = automaticSkip.also { automaticSkip = null }
    var done = false; private set
    var paused = true; private set
    val current: GuideStep? get() = steps.getOrNull(index)
    fun tick(now: Long, speedKph: Int?, countTime: Boolean = true): GuideStep? {
        if (done) return null
        if (!initialized) { initialized = true; lastMs = now; index = 0 }
        val delta = (now-lastMs).coerceIn(0,1000)
        lastMs = now
        if (!countTime) { paused = true; return null }
        // If the driver has already departed, never ask them to park or apply parked patterns in traffic.
        if (current?.moving == false && speedKph != null && speedKph > 5) {
            index = steps.indexOfFirst { it.moving }; accruedMs = 0; announced = false
        }
        val step = current ?: run { done = true; return null }
        val eligible = speedKph != null && if (step.moving) speedKph > 5 && (step.maxKph == null || speedKph <= step.maxKph)
            else speedKph == 0
        paused = !eligible
        if (!eligible) {
            if (step.maxKph != null && speedKph != null && speedKph > step.maxKph) {
                waitingMs += delta
                if (waitingMs >= 75000) { automaticSkip = step.id; advance(now); return null }
            }
            // Announce departure while still parked, but count this window only once moving.
            if (!announced && step.id == "depart_60" && speedKph != null) { announced = true; return step }
            return null
        }
        waitingMs = 0
        if (!announced) { announced = true; return step }
        accruedMs += delta
        if (accruedMs >= step.seconds*1000L) { advance(now) }
        return null
    }
    fun advance(now: Long) {
        if (done) return
        index++; accruedMs=0; announced=false; waitingMs=0; lastMs=now
        if (index >= steps.size) done = true
    }
    fun remainingSeconds(): Int = current?.let { (it.seconds-accruedMs/1000).toInt() } ?: 0
}
