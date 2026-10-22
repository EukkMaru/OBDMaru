package dev.obdmaru.recorder

/** Timed instruction windows, not declarations that the driver performed an action. */
data class GuideStep(val id: String, val seconds: Int, val moving: Boolean, val words: String,
                     val maxKph: Int? = null)
object GuidePlan {
    val steps = listOf(
        GuideStep("parked_baseline", 25, false, "Keep the car parked in P, parking brake on, engine idling. Leave the accelerator alone. Rest your foot off the foot brake for a baseline."),
        GuideStep("parked_light_brake", 25, false, "While still parked, press the foot brake lightly for two seconds, then release it fully. Repeat once. Leave the accelerator alone."),
        GuideStep("parked_firm_brake", 30, false, "Still parked in P, press the foot brake more firmly but comfortably for two seconds, then release. Then make two light taps followed by one firmer press. No accelerator input."),
        GuideStep("depart_60", 75, true, "Begin your normal journey when ready. If the road permits, build speed smoothly toward sixty kilometers per hour with light accelerator and comfortable low revs. Do not lug the engine, force a gear, or chase the target."),
        GuideStep("cruise_60", 55, true, "On the assumed ninety limit road, if appropriate, settle near sixty and enable cruise at your current speed. Lift your foot off the accelerator and let cruise hold it. Skip cruise if conditions do not suit."),
        GuideStep("cruise_70", 65, true, "If cruise is active and the road is clear, gradually raise its set speed toward seventy using the cruise buttons. Let the speed settle. Follow any lower posted limit instead; seventy is not required."),
        GuideStep("brake_cancel", 45, true, "At the next appropriate slowdown, brake gently to cancel cruise, then continue normally. Do not create an unnecessary stop. This is the brake cancellation observation window."),
        GuideStep("soft_gas", 40, true, "With cruise off, accelerate softly from your current pace using a light pedal, then ease off and coast naturally. This is about gentle pedal depth, not reaching a speed."),
        GuideStep("progressive_gas", 40, true, "When appropriate, gradually press the accelerator from light toward moderate for a few seconds, then ease back and release. Let the car coast. Choose your own timing and stay below the limit."),
        GuideStep("brief_full_gas", 40, true, "Optional stronger input, only with clear road and plenty of speed headroom. Smoothly press fully for about one second, ease back toward moderate pedal, then lift off. Skip this entirely if unsuitable. Do not sustain full throttle or chase a target speed.", maxKph = 60),
        GuideStep("light_brake", 35, true, "After coasting, use a light brake application when you next naturally need to slow. Release it and return to ordinary driving. There is no need to stop."),
        GuideStep("alternate_gas", 45, true, "If appropriate, build accelerator pressure smoothly from light to moderate, ease it back to light, then release and coast. A brief firmer input is optional. Do not pump the pedals or alternate brake and accelerator together.", maxKph = 65),
        GuideStep("firmer_brake", 40, true, "Coast first. At a naturally needed slowdown, you may brake more firmly than before, then release and settle. No emergency stop, target deceleration, or immediate full acceleration is requested."),
        GuideStep("cruise_resume", 60, true, "Optional cruise check. If its remembered speed is suitable, resume once and let it settle. Otherwise skip it. Then cancel with the cancel button when appropriate and continue with cruise off."),
        GuideStep("manual_prepare", 75, true, "The next section is optional manual shifting with cruise off. Settle at a steady legal pace, up to around ninety only if appropriate, leaving margin to the speed limit. Let D choose seventh if it naturally does. Do not accelerate just to reach seventh."),
        GuideStep("manual_seventh", 30, true, "If you are steady in seventh and comfortable, select manual mode and keep seventh for a moment. If not in seventh, skip the requested gear sequence and drive normally."),
        GuideStep("manual_sixth", 25, true, "If suitable at your current speed and revs, make one adjacent shift from seventh to sixth. Maintain a comfortable legal pace and let it settle. Skip any shift that would require high revs or excessive speed."),
        GuideStep("manual_fifth", 25, true, "If sixth is settled and revs allow, make one adjacent shift to fifth, then hold a steady comfortable pace. Do not force the downshift. Skip it if unsuitable."),
        GuideStep("manual_back_sixth", 25, true, "When appropriate, shift from fifth back to sixth and let it settle. Keep speed and traffic conditions ahead of the gear request."),
        GuideStep("manual_back_seventh", 25, true, "When appropriate, make the next adjacent upshift to seventh and let it settle. Then return to normal D when comfortable. No multi gear downshift is requested."),
        GuideStep("normal_finish", 60, true, "Continue your normal drive. The guided sequence is nearly finished. Recording will continue after the final instruction. Keep driving until you can park, then stop and export the session.")
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
    fun tick(now: Long, speedKph: Int?): GuideStep? {
        if (done) return null
        if (!initialized) { initialized = true; lastMs = now; index = 0 }
        val delta = (now-lastMs).coerceIn(0,1000)
        lastMs = now
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
