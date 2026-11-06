package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

/**
 * Keeps the live model supplied with the current camera view, timed to the user's speech, so the
 * scene the user is talking about is already in the turn when the server decides the turn ended.
 * No tool round trip.
 *
 * - Always (camera on, session live): one frame per [intervalMs], like Gemini Live's camera mode.
 *   Local voice detection alone missed real speech on phones (noise suppression lowers the level),
 *   so the steady cadence guarantees the model has a view no older than ~1s when the turn ends.
 * - Speech start: the newest frame goes out at once, without waiting for the cadence.
 * - Speech end (local silence for [endSilenceMs]): one last frame, because "이거" is often said last.
 *
 * Turn ending itself stays with the server's voice activity detection; local silence only decides
 * when to attach the closing frame, so a breath inside a sentence costs at most one extra frame.
 */
class SpeechSyncedVision(
    private val latestFrame: () -> Pair<String, Long>?,
    private val send: (String) -> Unit,
    private val maxFrameAgeMs: Long = 1_200L,
    private val intervalMs: Long = 1_000L,
    private val endSilenceMs: Long = 450L,
    private val maxFramesPerUtterance: Int = 8,
) {
    private var speaking = false
    private var lastVoiceAt = 0L
    private var lastSentAt = 0L
    private var lastSentFrameAt = 0L
    private var sentThisUtterance = 0

    /** Call for every captured audio chunk. [voiced] = chunk energy is above the speech threshold. */
    @Synchronized
    fun onAudio(voiced: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (voiced) {
            lastVoiceAt = nowMs
            if (!speaking) {
                speaking = true
                sentThisUtterance = 0
                sendLatest(nowMs)
            } else if (nowMs - lastSentAt >= intervalMs) {
                sendLatest(nowMs)
            }
        } else if (speaking && nowMs - lastVoiceAt >= endSilenceMs) {
            speaking = false
            sendLatest(nowMs, closing = true)
        } else if (nowMs - lastSentAt >= intervalMs) {
            sendLatest(nowMs, closing = true)
        }
    }

    @Synchronized
    fun reset() {
        speaking = false
        lastSentFrameAt = 0L
        sentThisUtterance = 0
    }

    private fun sendLatest(nowMs: Long, closing: Boolean = false) {
        if (sentThisUtterance >= maxFramesPerUtterance && !closing) return
        val (base64, capturedAt) = latestFrame() ?: return
        if (nowMs - capturedAt > maxFrameAgeMs) return // stale: better no picture than a wrong one
        if (capturedAt == lastSentFrameAt) return
        send(base64)
        lastSentAt = nowMs
        lastSentFrameAt = capturedAt
        sentThisUtterance += 1
    }
}
