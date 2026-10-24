package dev.obdmaru.recorder

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Uses installed Android TTS, with engine acceptance and actual utterance callbacks logged separately. */
class GuideSpeaker(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicInteger()
    private var tts: TextToSpeech? = null
    @Volatile var log: SessionLog? = null
    @Volatile var speaking = false; private set
    @Volatile private var currentUtterance: String? = null
    @Volatile var ready = false; private set
    @Volatile var state = "Loading spoken guidance…"; private set
    init {
        tts = TextToSpeech(context) { result ->
            val engine = tts
            if (result != TextToSpeech.SUCCESS || engine == null) { state = "Speech unavailable"; return@TextToSpeech }
            val language = engine.setLanguage(Locale.US)
            ready = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED
            state = if (ready) "English spoken guidance ready" else "Install an English TTS voice in Android settings"
            engine.setSpeechRate(0.95f)
            engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { record("speech_started", id) }
                override fun onDone(id: String?) { finish(id); record("speech_finished", id) }
                override fun onStop(id: String?, interrupted: Boolean) { finish(id); record("speech_stopped", id) }
                @Deprecated("TTS callback") override fun onError(id: String?) { fail(id); record("speech_error", id) }
                override fun onError(id: String?, errorCode: Int) { fail(id); record("speech_error", id, errorCode) }
            })
            log?.event("speech_ready", "ready" to ready, "state" to state)
        }
    }
    private fun fail(id: String?) {
        if (id == currentUtterance) { ready = false; speaking = false; state = "Speech failed; guide paused. Raw recording continues." }
    }
    private fun finish(id: String?) { if (id == currentUtterance) speaking = false }
    private fun record(type: String, id: String?, code: Int? = null) {
        try { log?.event(type, "utterance_id" to id, "code" to code) } catch (_: Exception) {}
    }
    fun say(words: String, stage: String): Boolean {
        if (!ready) return false
        val id = "$stage-${ids.incrementAndGet()}"
        currentUtterance = id; speaking = true
        log?.event("speech_queued", "utterance_id" to id, "stage" to stage, "words" to words)
        main.post {
            val result = tts?.speak(words, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result != TextToSpeech.SUCCESS) { fail(id); record("speech_error", id, result) }
        }
        return true
    }
    fun close() { ready = false; speaking = false; main.post { tts?.stop(); tts?.shutdown(); tts = null } }
}
