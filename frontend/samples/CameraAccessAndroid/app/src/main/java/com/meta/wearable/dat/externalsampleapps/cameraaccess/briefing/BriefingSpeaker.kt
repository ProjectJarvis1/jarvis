package com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * The phone's own Korean voice, for a briefing when no Jarvis conversation is running. It plays on the
 * media stream, which the glasses take over Bluetooth; it runs on the phone and costs nothing.
 */
class BriefingSpeaker(context: Context) {
    @Volatile private var ready = false
    private var pending: String? = null
    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        synchronized(this) {
            ready = status == TextToSpeech.SUCCESS &&
                tts.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
            if (!ready) Log.w("BriefingSpeaker", "Korean speech unavailable (status $status)")
            pending?.takeIf { ready }?.let { say(it) }
            pending = null
        }
    }

    fun speak(text: String) = synchronized(this) {
        if (ready) say(text) else pending = text
    }

    private fun say(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "brief-${System.currentTimeMillis()}")
    }

    fun release() = runCatching { tts.shutdown() }
}
