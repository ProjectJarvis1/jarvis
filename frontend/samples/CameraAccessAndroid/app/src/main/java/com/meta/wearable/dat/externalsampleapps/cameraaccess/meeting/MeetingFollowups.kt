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
    /** The transcript was edited and no longer holds [evidence]; kept in place so completion marks stay put. */
    val stale: Boolean = false,
)

object MeetingFollowups {
    private val KST: ZoneId = ZoneId.of("Asia/Seoul")

    /** Server `followups`; only meetings recorded before that field existed fall back to plain `action_items`. */
    fun parse(followups: JSONArray?, actionItems: List<String>): List<MeetingFollowup> {
        val parsed = (0 until (followups?.length() ?: 0)).mapNotNull { i ->
            val o = followups?.optJSONObject(i) ?: return@mapNotNull null
            val task = o.optString("task").trim()
            if (task.isEmpty()) null
            else MeetingFollowup(task, o.optString("owner").trim(), o.optString("due_text").trim(),
                o.optString("due_at").trim(), o.optString("evidence").trim(), o.optBoolean("stale", false))
        }
        // A verified list that came back empty stays empty: every item failed the check against the transcript.
        // Only records made before `followups` existed fall back to the unverified `action_items`.
        if (followups != null) return parsed
        return actionItems.filter { it.isNotBlank() }.map { MeetingFollowup(it.trim()) }
    }

    /**
     * The calendar day a follow-up is due, or null when the meeting did not pin one down. Only
     * unambiguous whole phrases resolve: an explicit date, 오늘/내일/모레, or a weekday ("금요일", "다음 주
     * 금요일"). "다음 주" alone, "다다음 주 금요일" and anything else stay null, and the user picks the date.
     */
    fun dueDate(item: MeetingFollowup, meetingStart: ZonedDateTime): LocalDate? {
        // "전에" (before) and "쯤", "즈음", "무렵", "전후" (around) are not "by": the user confirms those
        // dates, even when the server also filled due_at.
        if (QUALIFIED.containsMatchIn(item.dueText)) return null
        parseIsoDate(item.dueAt)?.let { return it }
        // Whole-phrase matches only: "다다음 주 금요일" or "다음 달 금요일" must not read as a nearer date.
        val text = item.dueText.replace(" ", "").replace(DUE_SUFFIX, "").ifBlank { return null }
        val today = meetingStart.withZoneSameInstant(KST).toLocalDate()
        EXPLICIT_DATE.matchEntire(text)?.let { m ->
            // The server may shorten "2028년 1월 5일" to "1월 5일"; the quoted sentence still has the year.
            val year = m.groupValues[1].toIntOrNull() ?: Regex("""(\d{4})년\s*${m.groupValues[2]}월\s*${m.groupValues[3]}일""")
                .find(item.evidence)?.groupValues?.get(1)?.toIntOrNull()
            val date = runCatching { LocalDate.of(year ?: today.year, m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
                .getOrNull() ?: return null
            // "1월 5일" said in December means next year; a stated year is kept as said.
            return if (year == null && date.isBefore(today.minusMonths(2))) date.plusYears(1) else date
        }
        when (text) {
            "오늘" -> return today
            "내일" -> return today.plusDays(1)
            "모레" -> return today.plusDays(2)
        }
        val m = WEEKDAY_PHRASE.matchEntire(text) ?: return null
        val weekday = WEEKDAYS.getValue(m.groupValues[2])
        return when (m.groupValues[1]) {
            "다음주" -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(weekday))
            "이번주" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .with(TemporalAdjusters.nextOrSame(weekday)).takeIf { !it.isBefore(today) }
            else -> today.with(TemporalAdjusters.next(weekday))
        }
    }

    /** A follow-up message the user sends themselves: they choose the recipient and press send. */
    fun draftMessage(decisions: List<String>, items: List<MeetingFollowup>, meetingStart: ZonedDateTime?): String =
        buildString {
            appendLine("안녕하세요. 회의에 함께해 주셔서 감사합니다.")
            appendLine("이야기 나눈 내용 정리해서 보내 드려요.")
            if (decisions.isNotEmpty()) {
                appendLine()
                appendLine("정한 내용")
                decisions.forEach { appendLine("- $it") }
            }
            val live = items.filterNot { it.stale }
            if (live.isNotEmpty()) {
                appendLine()
                appendLine("할 일")
                live.forEach { item ->
                    val due = meetingStart?.let { start -> dueDate(item, start)?.let { dueLabel(it, start) } }
                        ?: item.dueText.takeIf { it.isNotBlank() }
                    val extra = listOfNotNull(item.owner.takeIf { it.isNotBlank() }, due).joinToString(", ")
                    appendLine("- ${item.task}" + if (extra.isNotEmpty()) " ($extra)" else "")
                }
            }
            appendLine()
            append("빠진 내용이 있으면 편하게 말씀해 주세요.")
        }

    /** "11월 13일까지", with the year when it is not the meeting's year ("2027년 11월 20일까지"). */
    fun dueLabel(date: LocalDate, meetingStart: ZonedDateTime): String =
        (if (date.year != meetingStart.withZoneSameInstant(KST).year) "${date.year}년 " else "") +
            "${date.monthValue}월 ${date.dayOfMonth}일까지"

    fun parseIsoDate(value: String): LocalDate? {
        if (value.isBlank()) return null
        return runCatching { OffsetDateTime.parse(value).atZoneSameInstant(KST).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
    }

    fun parseStart(value: String): ZonedDateTime? =
        runCatching { OffsetDateTime.parse(value).atZoneSameInstant(KST) }.getOrNull()

    // Only endings that mean "by that day"; "전에" (before) and "쯤" (around) change the date, so they stay
    // in the text, the whole-phrase match fails, and the user picks the date.
    private val DUE_SUFFIX = Regex("""(까지|안에|중에|중|에)$""")
    private val QUALIFIED = Regex("""전에?\s*$|쯤|즈음|무렵|전후|경\s*$""")
    private val EXPLICIT_DATE = Regex("""(?:(\d{4})년)?(\d{1,2})월(\d{1,2})일""")
    private val WEEKDAY_PHRASE = Regex("""(이번주|다음주)?([월화수목금토일])요일""")
    private val WEEKDAYS = mapOf(
        "월" to DayOfWeek.MONDAY, "화" to DayOfWeek.TUESDAY, "수" to DayOfWeek.WEDNESDAY, "목" to DayOfWeek.THURSDAY,
        "금" to DayOfWeek.FRIDAY, "토" to DayOfWeek.SATURDAY, "일" to DayOfWeek.SUNDAY,
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
