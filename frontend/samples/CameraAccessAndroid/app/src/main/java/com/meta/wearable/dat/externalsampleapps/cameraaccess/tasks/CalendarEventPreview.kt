package com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/**
 * What a calendar entry will hold, in the words the approval card shows: title, the real day and time in
 * Korea time, and the place. The time is what the user must catch before approving, so it is spelled out
 * ("11월 20일 (목) 오후 4:00") instead of the raw ISO text the model produced.
 */
object CalendarEventPreview {
    private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    private val DAY = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
    private val TIME = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)

    data class Preview(val title: String, val time: String, val place: String?)

    fun of(argsJson: String): Preview {
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: JSONObject()
        fun arg(key: String) = args.optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
        return Preview(arg("title") ?: "제목 없는 일정", timeLabel(arg("start_at"), arg("end_at")), arg("location"))
    }

    /** "11월 20일 (목) 오후 4:00 ~ 오후 5:00"; a start the app cannot read is shown as given, never guessed. */
    fun timeLabel(startAt: String?, endAt: String?): String {
        val start = parse(startAt) ?: return startAt?.let { "시각 확인 필요: $it" } ?: "시각 정보 없음"
        val end = parse(endAt)
        val head = "${DAY.format(start)} ${TIME.format(start)}"
        return when {
            end == null -> head
            end.toLocalDate() == start.toLocalDate() -> "$head ~ ${TIME.format(end)}"
            else -> "$head ~ ${DAY.format(end)} ${TIME.format(end)}"
        }
    }

    /** Lines for a task list or card body: the time first, then the place. */
    fun lines(argsJson: String): List<String> = of(argsJson).let { listOfNotNull(it.title, it.time, it.place?.let { p -> "장소 $p" }) }

    private fun parse(value: String?) = value?.let { v ->
        runCatching { OffsetDateTime.parse(v).atZoneSameInstant(KST) }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(v).atZone(KST) }.getOrNull()
    }
}
