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
    @Volatile private var ready = false
    private var pending: String? = null
    private val _speaking = MutableStateFlow(false)
    /** Utterances queued and not finished; the engine's isSpeaking can lag behind onDone. */
    private val outstanding = mutableSetOf<String>()
    /** True while a briefing is being read; the wake word listener pauses so it does not hear it. */
    val speaking: StateFlow<Boolean> = _speaking
    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        synchronized(this) {
            ready = status == TextToSpeech.SUCCESS &&
                tts.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
            if (!ready) Log.w("BriefingSpeaker", "Korean speech unavailable (status $status)")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finished(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finished(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
            })
            pending?.takeIf { ready }?.let { say(it) }
            pending = null
        }
    }

    fun speak(text: String) = synchronized(this) {
        if (ready) say(text) else pending = text
    }

    private fun say(text: String) {
        val id = "brief-${System.nanoTime()}"
        // Marked before playback starts, so the listener is already paused for the first word.
        outstanding += id
        _speaking.value = true
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) finished(id)
    }

    private fun finished(id: String?) = synchronized(this) {
        outstanding.remove(id)
        if (outstanding.isEmpty()) _speaking.value = false
    }

    /** Cuts a briefing short (a conversation or a recording started). */
    fun stop() = synchronized(this) {
        pending = null
        if (ready) runCatching { tts.stop() }
        outstanding.clear()
        _speaking.value = false
    }

    fun release() {
        runCatching { tts.shutdown() }
        synchronized(this) { outstanding.clear() }
        _speaking.value = false
    }
}
