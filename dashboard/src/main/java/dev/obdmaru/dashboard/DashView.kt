package dev.obdmaru.dashboard

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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
    private val amber = Color.rgb(255, 198, 92)
    private val panel = Color.rgb(24, 34, 47)
    private val track = Color.rgb(33, 47, 63)
    private val redTrack = Color.rgb(124, 49, 62)
    private val redDots = DashPathEffect(floatArrayOf(1f, 6f), 0f)
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

    private fun rpmRing(c: Canvas, rpm: Double?) {
        ring(c, 123f, 8f, null, 6000.0, cyan)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        paint.color = redTrack
        paint.pathEffect = redDots
        c.drawArc(bounds, 345f, 25f, false, paint) // 4500–5000 RPM.
        paint.pathEffect = null
        c.drawArc(bounds, 370f, 50f, false, paint) // 5000–6000 RPM.
        if (rpm != null && rpm > 0) {
            val sweep = (300 * (rpm / 6000.0).coerceIn(0.0, 1.0)).toFloat()
            paint.color = cyan
            c.drawArc(bounds, 120f, minOf(sweep, 225f), false, paint)
            if (sweep > 225) {
                paint.color = red
                paint.pathEffect = redDots
                c.drawArc(bounds, 345f, minOf(sweep - 225f, 25f), false, paint)
                paint.pathEffect = null
                if (sweep > 250) c.drawArc(bounds, 370f, sweep - 250f, false, paint)
            }
        }
        paint.style = Paint.Style.FILL
        for (thousands in 1..5) {
            val angle = Math.toRadians(120.0 + 50 * thousands)
            text(c, thousands.toString(), 180 + 109 * cos(angle).toFloat(),
                225 + 109 * sin(angle).toFloat(), 8f, if (thousands >= 5) red else muted, centered = true)
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
                if (active) box(c, x - 21, 258f, 42f, 46f, track)
                text(c, gear.toString(), x, if (active) 298f else 291f,
                    if (active) 44f else if (prepared) 30f else 22f,
                    if (active) white else if (prepared) amber else dim, active || prepared, true)
                if (prepared) box(c, x - 10, 301f, 20f, 3f, amber)
            }
            c.restore()
            if (now - gearStarted < 180) postInvalidateOnAnimation()
        }
        val next = when (t?.nextGear) { in 1..7 -> t!!.nextGear.toString(); 14 -> "R"; else -> "—" }
        text(c, "NEXT? $next · b24", 180f, 320f, 10f,
            if (next == "—") muted else amber, true, true)
    }

    override fun onDraw(c: Canvas) {
        val scale = minOf(width / 360f, height / 650f)
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
        rpmRing(c, rpm)
        text(c, "km/h · OBD", 180f, 139f, 10f, muted, centered = true)
        text(c, ss?.bytes?.let { Telemetry.u(it, 0) }?.toString() ?: "—",
            180f, 202f, 66f, white, true, true)
        arrow(c, 94f, 174f, true, indicators?.left)
        arrow(c, 245f, 174f, false, indicators?.right)
        box(c, 108f, 218f, 144f, 1f, track)

        val mode = when (t?.mode) { 1 -> "P"; 3 -> "R"; 5 -> "N"; 7 -> "D"; 10 -> "M"; else -> "—" }
        val modeLabel = when (mode) { "P" -> "PARK"; "R" -> "REVERSE"; "N" -> "NEUTRAL"; "D" -> "AUTO"; "M" -> "MANUAL"; else -> "UNKNOWN" }
        val modeColor = if (mode == "M") amber else if (mode == "—") muted else cyan
        box(c, 129f, 226f, 102f, 29f, panel)
        text(c, mode, 140f, 250f, 26f, modeColor, true)
        text(c, modeLabel, 168f, 246f, 11f, modeColor, true)
        gears(c, t, now)
        text(c, number(rpm), 180f, 351f, 22f, if (rpm != null && rpm >= 4500) red else cyan, true, true)
        text(c, "RPM · 0–6000", 180f, 369f, 9f, muted, centered = true)

        fun pedal(x: Float, label: String, value: Double?, color: Int) {
            box(c, x, 390f, 162f, 55f, panel)
            text(c, label, x + 12, 407f, 10f, color, true)
            text(c, number(value) + if (value == null) "" else "%", x + 12, 434f, 26f, white, true)
        }
        pedal(12f, "OUTER · THROTTLE", t?.gas, green)
        pedal(186f, "MIDDLE · BRAKE", brake?.percent, red)
        text(c, "CLUTCH TEMPERATURES · PROVISIONAL", 12f, 461f, 9f, muted)
        fun temperature(x: Float, label: String, value: Double?) {
            box(c, x, 470f, 162f, 61f, panel)
            text(c, label, x + 12, 488f, 10f, cyan, true)
            text(c, number(value) + if (value == null) "" else "°C", x + 12, 520f, 30f, white, true)
        }
        temperature(12f, "CLUTCH 1", clutch?.clutch1)
        temperature(186f, "CLUTCH 2", clutch?.clutch2)
        text(c, "Brake √ curve · raw 150 = 100% · overflow stays numeric", 12f, 549f, 10f, muted)
        text(c, "Gas b07/08 ${t?.rawGas7 ?: "—"}/${t?.rawGas8 ?: "—"} · brake b32/33 ${brake?.raw32 ?: "—"}/${brake?.raw33 ?: "—"}",
            12f, 566f, 10f, muted)
        text(c, indicators?.let { String.format(Locale.US, "Turn candidate · b04 %02X", it.raw4) }
            ?: "Turn candidate · unavailable", 12f, 583f, 10f, muted)
        fun stat(name: String, s: Sample?) = if (s == null) "$name —" else
            String.format(Locale.US, "%s %.1fHz %dms", name, s.hz, now - s.at)
        text(c, stat("U19", ts) + " · " + stat("U03", bs), 12f, 600f, 10f, muted)
        text(c, stat("Speed", ss) + " · " + stat("RPM", rs), 12f, 616f, 10f, muted)
        text(c, stat("Clutch", cs) + " · " + stat("Turn", ins), 12f, 632f, 10f, muted)
        text(c, state.status.take(55), 12f, 648f, 10f, cyan)
        c.restore()
    }
}
