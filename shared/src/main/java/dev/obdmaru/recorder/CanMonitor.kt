package dev.obdmaru.recorder

import java.io.IOException

/** Strict ATH1/D1/CAF0, 11-bit CAN stream. Never interpret ISO-TP or guess offsets. */
class CanMonitorDecoder(private val frame: (ByteArray) -> Unit) {
    private val pending = StringBuilder()
    var prompted = false
        private set
    fun feed(text: String) {
        for (c in text) when (c) {
            '\r', '\n' -> line()
            '>' -> { line(); prompted = true }
            else -> { pending.append(c); if (pending.length > 128) throw IOException("Monitor line exceeds 128 characters") }
        }
    }
    private fun line() {
        val text = pending.toString().trim().uppercase().replace(Regex("\\s+"), "")
        pending.clear()
        if (text.isEmpty() || text == "ATMA" || text == "SEARCHING...") return
        if (!Regex("[0-9A-F]{20}").matches(text)) throw IOException("Unrecognized monitor output: $text")
        if (!text.startsWith("541")) throw IOException("Receive filter not honored: ${text.take(3)}")
        if (text[3] != '8') throw IOException("0x541 frame has unexpected DLC: ${text[3]}")
        frame(text.drop(4).chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }
}

/** Bounded stream lifecycle, independent of Android for fake serial-stream checks. */
object CanMonitor {
    fun run(now: () -> Long, active: () -> Boolean, read: () -> String?, write: (String) -> Unit,
            stopBoundary: () -> Boolean, tick: () -> Unit, frame: (ByteArray) -> Unit,
            durationMs: Long = 120000): String {
        require(durationMs in 1..120000)
        val start = now()
        var bytes = 0
        var frames = 0
        var failure: Throwable? = null
        val decoder = CanMonitorDecoder {
            if (++frames > 20000) throw IOException("Monitor frame budget exceeded")
            frame(it)
        }
        try {
            write("ATMA\r")
            while (active() && now() - start < durationMs) {
                tick()
                val text = read() ?: continue
                bytes += text.length
                if (bytes > 2 * 1024 * 1024) throw IOException("Monitor output budget exceeded")
                decoder.feed(text)
                if (decoder.prompted) throw IOException("Adapter ended monitoring early")
            }
            return if (active()) "time_limit" else "user_finished"
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            // Must abort even on a parse error, cancellation, empty bus or early prompt.
            // Caller closes the socket if this boundary cannot be established.
            try {
                if (!stopBoundary()) throw IOException("Monitor stop boundary unavailable; reconnect required")
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
            }
        }
    }
}
