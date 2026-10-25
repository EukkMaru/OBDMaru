package dev.obdmaru.dashboard

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import java.util.Locale
import kotlin.math.abs

class DashView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val white = Color.rgb(235, 241, 248)
    private val muted = Color.rgb(145, 164, 184)
    private val dim = Color.rgb(65, 84, 103)
    private val green = Color.rgb(58, 224, 153)
    private val red = Color.rgb(255, 87, 101)
    private val cyan = Color.rgb(67, 205, 227)
    private val amber = Color.rgb(185, 164, 127)
    private val panel = Color.rgb(24, 34, 47)
    private val track = Color.rgb(33, 47, 63)
    private val rpmBackground = Color.rgb(15, 25, 35)
    private val rpmFill = Color.rgb(22, 60, 67)
    private val rpmSurface = Color.rgb(41, 100, 106)
    private val rpmClip = Path().apply { addCircle(180f, 222f, 123f, Path.Direction.CW) }
    private val bounds = RectF()
    private var gearTarget: Int? = null
    private var gearFrom = 0f
    private var gearStarted = 0L

    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float,
                     color: Int = white, strong: Boolean = false, centered: Boolean = false) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = size
        paint.typeface = if (strong) bold else regular
        c.drawText(value, if (centered) x - paint.measureText(value) / 2 else x, y, paint)
    }

    private fun box(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawRoundRect(x, y, x + w, y + h, 10f, 10f, paint)
    }

    private fun number(value: Double?) = value?.let { String.format(Locale.US, "%.0f", it) } ?: "—"

    private fun arrow(c: Canvas, x: Float, y: Float, left: Boolean, on: Boolean?) {
        val path = Path()
        val points = listOf(0f to 8f, 9f to 0f, 9f to 5f, 21f to 5f,
            21f to 11f, 9f to 11f, 9f to 16f)
        points.forEachIndexed { i, (a, b) ->
            val px = x + if (left) a else 21 - a
            if (i == 0) path.moveTo(px, y + b) else path.lineTo(px, y + b)
        }
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = when (on) { true -> green; false -> dim; else -> muted }
        c.drawPath(path, paint)
        if (on == null) text(c, "?", x + 9, y + 12, 10f, panel, true, true)
    }

    // Android angles run clockwise from 3 o'clock: 120° -> 420° is 7 -> 5 o'clock.
    private fun ring(c: Canvas, radius: Float, width: Float, value: Double?, maximum: Double, color: Int) {
        bounds.set(180 - radius, 222 - radius, 180 + radius, 222 + radius)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = track
        c.drawArc(bounds, 120f, 300f, false, paint)
        if (value != null && value > 0) {
            paint.color = color
            c.drawArc(bounds, 120f, (300 * (value / maximum).coerceIn(0.0, 1.0)).toFloat(), false, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun rpmCircle(c: Canvas, rpm: Double?) {
        paint.style = Paint.Style.FILL
        paint.color = rpmBackground
        c.drawCircle(180f, 222f, 123f, paint)
        if (rpm != null && rpm > 0) {
            // A horizontal level rises through the whole disk, behind the readouts.
            // The level, rather than a sweep angle, maps linearly to 0–6000 RPM.
            val level = 345f - 246f * (rpm / 6000.0).coerceIn(0.0, 1.0).toFloat()
            c.save()
            c.clipPath(rpmClip)
            paint.color = rpmFill
            c.drawRect(57f, level, 303f, 345f, paint)
            paint.color = rpmSurface
            c.drawRect(57f, level, 303f, level + 1f, paint)
            c.restore()
        }
    }

    private fun gearPosition(now: Long): Float {
        val target = gearTarget ?: return 0f
        val progress = ((now - gearStarted) / 180f).coerceIn(0f, 1f)
        // Animate only the strip's position; each number always labels an actual gear.
        val eased = 1 - (1 - progress) * (1 - progress)
        return gearFrom + (target - gearFrom) * eased
    }

    private fun gears(c: Canvas, t: Transmission?, now: Long) {
        val current = Telemetry.gear(t)
        val selected = current.toIntOrNull()?.takeIf { it in 1..7 }
        if (selected == null) {
            gearTarget = null
            text(c, current, 180f, 300f, 46f, white, true, true)
        } else {
            if (gearTarget != selected) {
                val previous = gearPosition(now)
                gearFrom = if (gearTarget == null || abs(selected - previous) > 1.1f) selected.toFloat() else previous
                gearTarget = selected
                gearStarted = now
            }
            val position = gearPosition(now)
            c.save()
            c.clipRect(94f, 257f, 266f, 305f)
            for (gear in 1..7) {
                val x = 180 + (gear - position) * 47
                val active = gear == selected
                val prepared = gear == t?.nextGear
                text(c, gear.toString(), x, if (active) 298f else 291f,
                    if (active) 42f else if (prepared) 24f else 22f,
                    if (active) white else if (prepared) amber else dim, active, true)
                if (prepared) box(c, x - 7, 301f, 14f, 1f, amber)
            }
            c.restore()
            if (now - gearStarted < 180) postInvalidateOnAnimation()
        }
        val next = when (t?.nextGear) { in 1..7 -> t!!.nextGear.toString(); 14 -> "R"; else -> "—" }
        text(c, "NEXT? $next · b24", 180f, 320f, 9f, muted, centered = true)
    }

    override fun onDraw(c: Canvas) {
        val scale = minOf(width / 360f, height / 640f)
        c.save()
        c.translate((width - 360 * scale) / 2, 0f)
        c.scale(scale, scale)
        val state = DashboardService.state
        val now = SystemClock.elapsedRealtime()
        fun sample(key: String, age: Long = 1500) = state.samples[key]?.takeIf {
            DashboardService.running && Telemetry.fresh(it.at, now, age)
        }
        val ts = sample("tcm"); val bs = sample("brake"); val ss = sample("speed")
        val rs = sample("rpm"); val ins = sample("indicators"); val cs = sample("clutch", 2500)
        val coolantSample = sample("coolant", 2500)
        val t = ts?.let { Telemetry.transmission(it.bytes) }
        val brake = bs?.let { Telemetry.brake(it.bytes) }
        val clutch = cs?.let { Telemetry.transmission(it.bytes) }
        val indicators = ins?.let { Telemetry.indicators(it.bytes) }
        val rpm = rs?.let { Telemetry.rpm(it.bytes) }

        text(c, "OBDMARU", 12f, 23f, 13f, white, true)
        text(c, if (state.demo) "SYNTHETIC DEMO" else "LIVE · EXPERIMENTAL", 275f, 23f, 9f, cyan,
            centered = true)

        ring(c, 160f, 13f, t?.gas, 100.0, green)
        ring(c, 142f, 13f, brake?.percent, 100.0, red)
        rpmCircle(c, rpm)
        text(c, "km/h · OBD", 180f, 139f, 10f, muted, centered = true)
        text(c, ss?.bytes?.let { Telemetry.u(it, 0) }?.toString() ?: "—",
            180f, 202f, 66f, white, true, true)
        arrow(c, 94f, 174f, true, indicators?.left)
        arrow(c, 245f, 174f, false, indicators?.right)
        box(c, 108f, 218f, 144f, 1f, track)

        val mode = when (t?.mode) { 1 -> "P"; 3 -> "R"; 5 -> "N"; 7 -> "D"; 10 -> "M"; else -> "—" }
        val modeColor = if (mode == "M") amber else if (mode == "—") muted else cyan
        box(c, 156f, 226f, 48f, 29f, panel)
        text(c, mode, 180f, 250f, 26f, modeColor, true, true)
        gears(c, t, now)
        text(c, number(rpm), 180f, 365f, 20f, muted, centered = true)
        text(c, if (rpm == null) "RPM · unavailable" else "RPM · 0–6000", 180f, 380f, 9f, muted, centered = true)

        text(c, "OUTER · THROTTLE", 12f, 407f, 10f, green, true)
        text(c, "MIDDLE · BRAKE", 186f, 407f, 10f, red, true)
        text(c, "TEMPERATURES · CLUTCHES PROVISIONAL", 12f, 434f, 9f, muted)
        fun temperature(x: Float, label: String, value: Double?) {
            box(c, x, 443f, 104f, 61f, panel)
            text(c, label, x + 10, 461f, 10f, cyan, true)
            text(c, number(value) + if (value == null) "" else "°C", x + 10, 493f, 24f, white, true)
        }
        temperature(12f, "COOLANT", coolantSample?.let { Telemetry.coolant(it.bytes) })
        temperature(128f, "CLUTCH 1", clutch?.clutch1)
        temperature(244f, "CLUTCH 2", clutch?.clutch2)
        text(c, "Brake √ curve · raw 150 = full arc", 12f, 522f, 10f, muted)
        text(c, "Gas b07/08 ${t?.rawGas7 ?: "—"}/${t?.rawGas8 ?: "—"} · brake b32/33 ${brake?.raw32 ?: "—"}/${brake?.raw33 ?: "—"}",
            12f, 539f, 10f, muted)
        text(c, indicators?.let { String.format(Locale.US, "Turn candidate · b04 %02X", it.raw4) }
            ?: "Turn candidate · unavailable", 12f, 556f, 10f, muted)
        fun stat(name: String, s: Sample?) = if (s == null) "$name —" else
            String.format(Locale.US, "%s %.1fHz %dms", name, s.hz, now - s.at)
        text(c, stat("U19", ts) + " · " + stat("U03", bs), 12f, 573f, 10f, muted)
        text(c, stat("Speed", ss) + " · " + stat("RPM", rs), 12f, 589f, 10f, muted)
        text(c, stat("Clutch", cs) + " · " + stat("Turn", ins), 12f, 605f, 10f, muted)
        text(c, stat("Coolant", coolantSample), 12f, 621f, 10f, muted)
        text(c, state.status.take(55), 12f, 638f, 10f, cyan)
        c.restore()
    }
}
