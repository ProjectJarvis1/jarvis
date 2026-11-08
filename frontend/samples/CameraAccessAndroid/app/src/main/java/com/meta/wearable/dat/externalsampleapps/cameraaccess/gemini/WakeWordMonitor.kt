package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingVoiceCommand
import com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingVoiceCommandParser
import java.util.Locale

class WakeWordMonitor(
    context: Context,
    private val onWakeWord: (String) -> Unit,
    private val onMeetingCommand: ((MeetingVoiceCommand, String) -> Unit)? = null,
    private val onStatus: ((String) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "WakeWordMonitor"
        private const val WAKE_WORD = "자비스"

        fun stripWakeWord(text: String): String {
            return text
                .replaceFirst(Regex("^\\s*(자비스|jarvis)\\s*[,.!?:;，]?", RegexOption.IGNORE_CASE), "")
                .trim()
        }
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile
    private var isRunning = false
    @Volatile
    private var released = false
    @Volatile
    private var wakeWordFired = false
    private var consecutiveFailures = 0
    /** Between start() and stop(); a late recognizer callback after stop() must not listen again. */
    @Volatile
    private var wanted = false

    fun start() {
        if (released) return
        wanted = true
        if (isRunning) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onStatus?.invoke("이 휴대폰에서는 음성 인식을 쓸 수 없어 '자비스' 호출이 동작하지 않아요.")
            return
        }
        mainHandler.post {
            if (isRunning || released || !wanted) return@post
            // A detection that stopped the last session may never get its terminal callback.
            wakeWordFired = false
            consecutiveFailures = 0
            createRecognizer()
            listen()
        }
    }

    fun stop() {
        if (released) return
        wanted = false
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    fun release() {
        released = true
        stop()
    }

    private fun createRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    Log.d(TAG, "Wake word listener ready")
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    handleRecognitionBundle(partialResults)
                }

                override fun onResults(results: android.os.Bundle?) {
                    handleRecognitionBundle(results)
                    scheduleRestart(null)
                }

                override fun onError(error: Int) {
                    Log.d(TAG, "Wake word error=$error")
                    scheduleRestart(error)
                }

                override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            })
        }
    }

    private fun listen() {
        val recognizer = speechRecognizer ?: return
        if (released || !wanted) return

        isRunning = true
        WakeWordTrialLog.update(appContext) { it.listened() }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word listener: ${e.message}")
            scheduleRestart(-1)
        }
    }

    private fun handleRecognitionBundle(bundle: android.os.Bundle?) {
        if (bundle == null || wakeWordFired || released) return

        val texts = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()

        val commandText = texts.firstOrNull { MeetingVoiceCommandParser.parse(it) != null }
        if (commandText != null) {
            wakeWordFired = true
            val command = MeetingVoiceCommandParser.parse(commandText) ?: return
            Log.d(TAG, "Meeting voice command detected: $command from: $commandText")
            mainHandler.post {
                speechRecognizer?.cancel()
                onMeetingCommand?.invoke(command, commandText)
            }
            return
        }

        val matched = texts.firstOrNull { isWakeWordText(it) } ?: return
        wakeWordFired = true
        Log.d(TAG, "Wake word detected from: $matched")
        WakeWordTrialLog.update(appContext) {
            it.detected(System.currentTimeMillis(), WakeWordTrialLog.screenOff(appContext))
        }
        mainHandler.post {
            speechRecognizer?.cancel()
            onWakeWord(matched)
        }
    }

    private fun isWakeWordText(text: String): Boolean {
        val normalized = text
            .lowercase()
            .replace(Regex("[^가-힣a-z0-9]"), "")
        return normalized.contains(WAKE_WORD) ||
            normalized.contains("jarvis") ||
            normalized.startsWith("비스") ||
            normalized.startsWith("자비") ||
            normalized.contains("라비스")
    }

    /**
     * Listens again after [error] (null = a normal result). Repeated real failures back off instead of
     * restarting every 150ms, so a recognizer the system refuses in the background does not spin.
     */
    private fun scheduleRestart(error: Int?) {
        if (released || !wanted) return
        isRunning = false
        wakeWordFired = false
        if (WakeWordRetry.isNormal(error)) {
            consecutiveFailures = 0
        } else {
            WakeWordTrialLog.update(appContext) { it.failed(error ?: -1, System.currentTimeMillis()) }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                onStatus?.invoke("휴대폰이 백그라운드 마이크 사용을 막았어요. (오류 코드 9)")
            }
        }
        val delay = WakeWordRetry.delayMs(error, consecutiveFailures)
        if (!WakeWordRetry.isNormal(error)) consecutiveFailures++
        mainHandler.postDelayed({
            if (!released && wanted && !isRunning) {
                listen()
            }
        }, delay)
    }
}
