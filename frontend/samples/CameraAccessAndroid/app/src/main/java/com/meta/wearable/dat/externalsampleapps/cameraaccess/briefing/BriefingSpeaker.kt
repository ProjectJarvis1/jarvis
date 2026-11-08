package com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone's own Korean voice, for a briefing when no Jarvis conversation is running. It plays on the
 * media stream, which the glasses take over Bluetooth; it runs on the phone and costs nothing.
 */
class BriefingSpeaker(context: Context) {
    private var engine: TextToSpeech? = null
    /** Set when the engine reported before its constructor returned (Android may do that on failure). */
    private var earlyStatus: Int? = null
    @Volatile private var ready = false
    @Volatile private var failed = false
    private var pending: String? = null
    private val _speaking = MutableStateFlow(false)
    /** True while a briefing is being read; the wake word listener pauses so it does not hear it. */
    val speaking: StateFlow<Boolean> = _speaking
    /** Utterances queued and not finished; the engine's isSpeaking can lag behind onDone. */
    private val outstanding = mutableSetOf<String>()

    init {
        val created = runCatching { TextToSpeech(context) { status -> onInit(status) } }.getOrNull()
        synchronized(this) {
            engine = created
            earlyStatus?.let { onInit(it) }
        }
    }

    private fun onInit(status: Int) = synchronized(this) {
        val tts = engine
        if (tts == null) { earlyStatus = status; return@synchronized }
        if (status != TextToSpeech.SUCCESS || tts.setLanguage(Locale.KOREAN) < TextToSpeech.LANG_AVAILABLE) {
            Log.w("BriefingSpeaker", "Korean speech unavailable (status $status)")
            failed = true
            pending = null
            return@synchronized
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
        ready = true
        pending?.let { say(tts, it) }
        pending = null
    }

    /** False when the phone cannot speak Korean (no engine or no voice), so the caller may retry later. */
    fun speak(text: String): Boolean = synchronized(this) {
        val tts = engine
        when {
            failed || tts == null -> false
            ready -> say(tts, text)
            else -> { pending = text; true }
        }
    }

    private fun say(tts: TextToSpeech, text: String): Boolean {
        val id = "brief-${System.nanoTime()}"
        // Marked before playback starts, so the listener is already paused for the first word.
        outstanding += id
        _speaking.value = true
        val ok = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS
        if (!ok) finished(id)
        return ok
    }

    private fun finished(id: String?) = synchronized(this) {
        outstanding.remove(id)
        if (outstanding.isEmpty()) _speaking.value = false
    }

    /** Cuts the current and any queued briefing short (a conversation or a recording started, or the switch went off). */
    fun stop() = synchronized(this) {
        pending = null
        if (ready) runCatching { engine?.stop() }
        outstanding.clear()
        _speaking.value = false
    }

    fun release() = synchronized(this) {
        runCatching { engine?.shutdown() }
        ready = false
        pending = null
        outstanding.clear()
        _speaking.value = false
    }
}
