package com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import org.json.JSONArray

/**
 * One thing someone promised in a meeting, as the server extracted it. [owner], [dueText] and [dueAt]
 * are only filled when the transcript says so ([evidence] is the quoted sentence); they are never guessed.
 */
data class MeetingFollowup(
    val task: String,
    val owner: String = "",
    val dueText: String = "",
    val dueAt: String = "",
    val evidence: String = "",
)

object MeetingFollowups {
    private val KST: ZoneId = ZoneId.of("Asia/Seoul")

    /** Server `followups`; meetings recorded before that existed fall back to plain `action_items`. */
    fun parse(followups: JSONArray?, actionItems: List<String>): List<MeetingFollowup> {
        val parsed = (0 until (followups?.length() ?: 0)).mapNotNull { i ->
            val o = followups?.optJSONObject(i) ?: return@mapNotNull null
            val task = o.optString("task").trim()
            if (task.isEmpty()) null
            else MeetingFollowup(task, o.optString("owner").trim(), o.optString("due_text").trim(),
                o.optString("due_at").trim(), o.optString("evidence").trim())
        }
        return parsed.ifEmpty { actionItems.filter { it.isNotBlank() }.map { MeetingFollowup(it.trim()) } }
    }

    /**
     * The calendar day a follow-up is due, or null when the meeting did not pin one down. Only
     * unambiguous phrases resolve: an explicit date, 오늘/내일/모레, or a weekday ("금요일", "다음 주
     * 금요일"). "다음 주" alone has no day, so it stays null and the user picks the date.
     */
    fun dueDate(item: MeetingFollowup, meetingStart: ZonedDateTime): LocalDate? {
        parseIsoDate(item.dueAt)?.let { return it }
        val text = item.dueText.ifBlank { return null }.replace(" ", "")
        val today = meetingStart.withZoneSameInstant(KST).toLocalDate()
        Regex("""(\d{1,2})월(\d{1,2})일""").find(text)?.let { m ->
            val month = m.groupValues[1].toInt()
            val day = m.groupValues[2].toInt()
            val date = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
            // "1월 5일" said in December means next year.
            return if (date.isBefore(today.minusMonths(2))) date.plusYears(1) else date
        }
        when {
            text.startsWith("오늘") -> return today
            text.startsWith("내일") -> return today.plusDays(1)
            text.startsWith("모레") -> return today.plusDays(2)
        }
        val weekday = WEEKDAYS.entries.firstOrNull { text.contains(it.key) }?.value ?: return null
        return when {
            text.contains("다음주") -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(weekday))
            text.contains("이번주") -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .with(TemporalAdjusters.nextOrSame(weekday)).takeIf { !it.isBefore(today) }
            else -> today.with(TemporalAdjusters.next(weekday))
        }
    }

    /** A follow-up message the user sends themselves: they choose the recipient and press send. */
    fun draftMessage(decisions: List<String>, items: List<MeetingFollowup>, meetingStart: ZonedDateTime?): String =
        buildString {
            appendLine("안녕하세요. 오늘 회의 감사합니다.")
            appendLine("이야기 나눈 내용 정리해서 보내 드려요.")
            if (decisions.isNotEmpty()) {
                appendLine()
                appendLine("정한 내용")
                decisions.forEach { appendLine("- $it") }
            }
            if (items.isNotEmpty()) {
                appendLine()
                appendLine("할 일")
                items.forEach { item ->
                    val due = meetingStart?.let { dueDate(item, it) }?.let { "${it.monthValue}월 ${it.dayOfMonth}일까지" }
                        ?: item.dueText.takeIf { it.isNotBlank() }
                    val extra = listOfNotNull(item.owner.takeIf { it.isNotBlank() }, due).joinToString(", ")
                    appendLine("- ${item.task}" + if (extra.isNotEmpty()) " ($extra)" else "")
                }
            }
            appendLine()
            append("빠진 내용이 있으면 편하게 말씀해 주세요.")
        }

    fun parseIsoDate(value: String): LocalDate? {
        if (value.isBlank()) return null
        return runCatching { OffsetDateTime.parse(value).atZoneSameInstant(KST).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
    }

    fun parseStart(value: String): ZonedDateTime? =
        runCatching { OffsetDateTime.parse(value).atZoneSameInstant(KST) }.getOrNull()

    private val WEEKDAYS = linkedMapOf(
        "월요일" to DayOfWeek.MONDAY, "화요일" to DayOfWeek.TUESDAY, "수요일" to DayOfWeek.WEDNESDAY,
        "목요일" to DayOfWeek.THURSDAY, "금요일" to DayOfWeek.FRIDAY, "토요일" to DayOfWeek.SATURDAY,
        "일요일" to DayOfWeek.SUNDAY,
    )
}

/**
 * What the user already did with each follow-up, kept on the phone so reopening a meeting shows it.
 * "일정 화면을 열었다" is recorded as such: whether the calendar entry was saved is up to the calendar app.
 */
object MeetingFollowupStatus {
    enum class Step { CALENDAR_OPENED, DONE }

    private const val PREFS = "meeting_followups"

    fun read(context: Context, meetingId: String, index: Int): Set<Step> =
        prefs(context).getStringSet(key(meetingId, index), emptySet()).orEmpty()
            .mapNotNull { runCatching { Step.valueOf(it) }.getOrNull() }.toSet()

    fun set(context: Context, meetingId: String, index: Int, step: Step, on: Boolean) {
        val next = read(context, meetingId, index).let { if (on) it + step else it - step }
        prefs(context).edit().putStringSet(key(meetingId, index), next.map { it.name }.toSet()).apply()
    }

    fun messageDrafted(context: Context, meetingId: String): Long = prefs(context).getLong("$meetingId:message", 0L)

    fun markMessageDrafted(context: Context, meetingId: String) =
        prefs(context).edit().putLong("$meetingId:message", System.currentTimeMillis()).apply()

    private fun key(meetingId: String, index: Int) = "$meetingId:$index"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
