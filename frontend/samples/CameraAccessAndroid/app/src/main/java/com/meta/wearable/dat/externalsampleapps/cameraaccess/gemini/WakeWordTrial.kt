package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * When to listen again after the speech recognizer ended. "Nothing heard" (6, 7) is the normal loop
 * and restarts right away; anything else backs off so a refused or busy recognizer does not spin.
 * Error 9 (no mic permission) is what a background mic refusal looks like, so it waits longest and
 * keeps trying, in case the app comes back to the screen.
 */
object WakeWordRetry {
    const val NORMAL_MS = 150L
    const val MAX_MS = 10_000L
    const val PERMISSION_MS = 30_000L

    fun delayMs(error: Int?, consecutiveFailures: Int): Long = when (error) {
        null, 6, 7 -> NORMAL_MS
        9 -> PERMISSION_MS
        else -> (500L shl consecutiveFailures.coerceIn(0, 5)).coerceAtMost(MAX_MS)
    }

    /** Errors that are part of the normal listen loop, not failures. */
    fun isNormal(error: Int?): Boolean = error == null || error == 6 || error == 7
}

/**
 * A running record of the wake word trial, kept on the phone so the tester can read the result in
 * Settings instead of logcat: how often it heard "자비스" with the screen off, what errors stopped
 * it, and how much battery went since the trial started.
 */
data class WakeWordTrial(
    val startedAt: Long = 0L,
    val batteryAtStart: Int = -1,
    val listenStarts: Int = 0,
    val detections: Int = 0,
    val detectionsScreenOff: Int = 0,
    val lastDetectionAt: Long = 0L,
    val errors: Map<Int, Int> = emptyMap(),
    val lastError: Int? = null,
    val lastErrorAt: Long = 0L,
    val serviceNote: String? = null,
) {
    fun started(now: Long, battery: Int) = if (startedAt == 0L) copy(startedAt = now, batteryAtStart = battery) else this
    fun listened() = copy(listenStarts = listenStarts + 1)
    fun detected(now: Long, screenOff: Boolean) =
        copy(detections = detections + 1, detectionsScreenOff = detectionsScreenOff + if (screenOff) 1 else 0, lastDetectionAt = now)
    fun failed(error: Int, now: Long) =
        copy(errors = errors + (error to (errors[error] ?: 0) + 1), lastError = error, lastErrorAt = now)

    /** Several short lines for the Settings screen. */
    fun summary(now: Long, batteryNow: Int): List<String> {
        if (startedAt == 0L) return listOf("아직 기록이 없어요. 대화 화면을 열면 기록을 시작해요.")
        val hours = (now - startedAt) / 3_600_000.0
        val lines = mutableListOf(
            "${time(startedAt)}부터 ${"%.1f".format(hours)}시간 기록",
            "호출 인식 ${detections}번, 그중 화면 꺼짐 ${detectionsScreenOff}번" +
                if (lastDetectionAt > 0) " (마지막 ${time(lastDetectionAt)})" else "",
        )
        if (batteryAtStart in 0..100 && batteryNow in 0..100) {
            val used = batteryAtStart - batteryNow
            lines += if (hours >= 0.25 && used >= 0) "배터리 ${batteryAtStart}%에서 ${batteryNow}%, 시간당 약 ${"%.1f".format(used / hours)}%"
            else "배터리 ${batteryAtStart}%에서 ${batteryNow}%"
        }
        val failures = errors.filterKeys { !WakeWordRetry.isNormal(it) }
        lines += if (failures.isEmpty()) "듣기 오류 없음 (다시 듣기 ${listenStarts}번)"
        else "듣기 오류 " + failures.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}번 ${it.value}회" } +
            (lastError?.takeIf { !WakeWordRetry.isNormal(it) }?.let { " (마지막 ${it}번, ${time(lastErrorAt)})" } ?: "")
        serviceNote?.let { lines += it }
        return lines
    }

    private fun time(ms: Long) = FORMAT.format(Instant.ofEpochMilli(ms))

    companion object {
        private val FORMAT = DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.of("Asia/Seoul"))
    }
}

/** Keeps [WakeWordTrial] in SharedPreferences. Every call is cheap and safe from any thread. */
object WakeWordTrialLog {
    private const val PREFS = "wake_word_trial"
    @Volatile private var cached: WakeWordTrial? = null

    @Synchronized
    fun read(context: Context): WakeWordTrial = cached ?: load(context).also { cached = it }

    @Synchronized
    fun update(context: Context, change: (WakeWordTrial) -> WakeWordTrial) {
        val next = change(read(context))
        cached = next
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit()
            .putLong("startedAt", next.startedAt).putInt("batteryAtStart", next.batteryAtStart)
            .putInt("listenStarts", next.listenStarts).putInt("detections", next.detections)
            .putInt("detectionsScreenOff", next.detectionsScreenOff).putLong("lastDetectionAt", next.lastDetectionAt)
            .putString("errors", next.errors.entries.joinToString(",") { "${it.key}:${it.value}" })
            .putInt("lastError", next.lastError ?: -1).putLong("lastErrorAt", next.lastErrorAt)
            .putString("serviceNote", next.serviceNote)
            .apply()
    }

    /** Starts a fresh record from now, as if the trial had just begun. */
    @Synchronized
    fun reset(context: Context) {
        cached = WakeWordTrial()
        update(context) { it.started(System.currentTimeMillis(), battery(context)) }
    }

    fun battery(context: Context): Int =
        runCatching { context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
            .getOrDefault(-1)

    fun screenOff(context: Context): Boolean =
        runCatching { !context.getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(false)

    private fun load(context: Context): WakeWordTrial {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WakeWordTrial(
            startedAt = p.getLong("startedAt", 0L),
            batteryAtStart = p.getInt("batteryAtStart", -1),
            listenStarts = p.getInt("listenStarts", 0),
            detections = p.getInt("detections", 0),
            detectionsScreenOff = p.getInt("detectionsScreenOff", 0),
            lastDetectionAt = p.getLong("lastDetectionAt", 0L),
            errors = p.getString("errors", "").orEmpty().split(",").mapNotNull { part ->
                val (k, v) = part.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
                val code = k.toIntOrNull() ?: return@mapNotNull null
                val count = v.toIntOrNull() ?: return@mapNotNull null
                code to count
            }.toMap(),
            lastError = p.getInt("lastError", -1).takeIf { it >= 0 },
            lastErrorAt = p.getLong("lastErrorAt", 0L),
            serviceNote = p.getString("serviceNote", null),
        )
    }
}
