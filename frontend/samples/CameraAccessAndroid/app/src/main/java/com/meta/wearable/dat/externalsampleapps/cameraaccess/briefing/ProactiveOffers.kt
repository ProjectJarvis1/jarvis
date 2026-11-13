package com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** One calendar occurrence as the phone stores it. All-day BEGIN is midnight UTC of that date. */
data class CalendarItem(
    val key: String,
    val title: String,
    val beginMs: Long,
    val allDay: Boolean,
    val location: String = "",
    val attendees: List<String> = emptyList(),
) {
    fun asMeeting() = UpcomingMeeting(key, title, beginMs, location, attendees)
}

enum class OfferKind { MEETING, DEADLINE }

/** Something Jarvis offers on its own, before the user asks. [detail] is what opens when it is accepted. */
data class ProactiveOffer(
    val id: String,
    val kind: OfferKind,
    val title: String,
    val message: String,
    val detail: String,
    val createdAtMs: Long,
    val eventBeginMs: Long,
)

/** A deadline offer is sent twice at most: a few days ahead, then the day before. */
enum class DeadlineStage { EARLY, EVE }

/**
 * When and how Jarvis speaks first. Two offers:
 * - the day's meeting, about two hours before, only when stored records are about it;
 * - a deadline (제출, 마감, 입찰 ...) in the coming days, three days ahead and again the day before.
 * Offers go out between 08:00 and 21:00 KST, at most [MAX_PER_DAY] a day, once per occurrence and stage.
 */
object ProactivePlanner {
    val KST: ZoneId = ZoneId.of("Asia/Seoul")
    const val MEETING_LEAD_MAX_MS = 2 * 3_600_000L
    /** Closer than this, the 5 to 12 minute briefing covers the meeting. */
    const val MEETING_LEAD_MIN_MS = 20 * 60_000L
    const val DEADLINE_LEAD_DAYS = 3L
    const val LOOKAHEAD_DAYS = 7L
    const val QUIET_FROM_HOUR = 21
    const val QUIET_UNTIL_HOUR = 8
    const val MAX_PER_DAY = 4

    private val DEADLINE_WORDS = listOf("제출", "마감", "입찰", "기한", "납기", "신청", "접수", "서류")
    private val WEEKDAYS = listOf("월", "화", "수", "목", "금", "토", "일")

    fun isDeadline(title: String): Boolean = DEADLINE_WORDS.any { title.contains(it) }

    /** True while offers may be sent: 08:00 up to 21:00 in Korea. */
    fun withinHours(nowMs: Long): Boolean {
        val hour = Instant.ofEpochMilli(nowMs).atZone(KST).hour
        return hour in QUIET_UNTIL_HOUR until QUIET_FROM_HOUR
    }

    /** The Korean calendar date of an item: all-day items are stored at UTC midnight, timed ones in absolute time. */
    fun localDate(item: CalendarItem): LocalDate =
        if (item.allDay) Instant.ofEpochMilli(item.beginMs).atZone(ZoneOffset.UTC).toLocalDate()
        else Instant.ofEpochMilli(item.beginMs).atZone(KST).toLocalDate()

    fun today(nowMs: Long): LocalDate = Instant.ofEpochMilli(nowMs).atZone(KST).toLocalDate()

    /** A timed, non-deadline event starting 20 minutes to 2 hours from now, today in Korea. */
    fun meetingDue(item: CalendarItem, nowMs: Long): Boolean {
        if (item.allDay || isDeadline(item.title)) return false
        val lead = item.beginMs - nowMs
        return lead in MEETING_LEAD_MIN_MS..MEETING_LEAD_MAX_MS && localDate(item) == today(nowMs)
    }

    /** Which deadline offer is due now, if any. A timed deadline whose time already passed today is not offered. */
    fun deadlineStage(item: CalendarItem, nowMs: Long): DeadlineStage? {
        if (!isDeadline(item.title)) return null
        if (!item.allDay && item.beginMs <= nowMs) return null
        val days = ChronoUnit.DAYS.between(today(nowMs), localDate(item))
        return when (days) {
            1L -> DeadlineStage.EVE
            in 2L..DEADLINE_LEAD_DAYS -> DeadlineStage.EARLY
            else -> null
        }
    }

    fun meetingKey(item: CalendarItem) = "meet:${item.key}"
    fun deadlineKey(item: CalendarItem, stage: DeadlineStage) = "due:${item.key}:${stage.name}"

    /** How many offers were already sent today (KST), from the dedupe store's claim times. */
    fun sentToday(claimed: Map<String, Long>, nowMs: Long): Int {
        val day = today(nowMs)
        return claimed.values.count { Instant.ofEpochMilli(it).atZone(KST).toLocalDate() == day }
    }

    /** "오후 2시", "오전 10시 30분". */
    fun clock(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(KST)
        val half = if (t.hour < 12) "오전" else "오후"
        val h = (t.hour % 12).let { if (it == 0) 12 else it }
        return if (t.minute == 0) "$half ${h}시" else "$half ${h}시 ${t.minute}분"
    }

    /** "11월 17일(화)". */
    fun dateLabel(date: LocalDate): String = "${date.monthValue}월 ${date.dayOfMonth}일(${WEEKDAYS[date.dayOfWeek.value - 1]})"

    /**
     * "오늘 오후 2시에 '한빛정밀 견적 미팅' 일정이 있으시네요. 최근 김민수 님과 나눈 내용 정리해 드릴까요?"
     * The "○○ 님과 나눈 내용" wording only for someone a found meeting or note names ([historyWith]); other
     * history is "관련 기록", and a saved contact alone is offered as stored information, not as a conversation.
     */
    fun meetingMessage(item: CalendarItem, people: List<String>, hasHistory: Boolean, historyWith: List<String> = emptyList()): String {
        val offer = when {
            historyWith.isNotEmpty() -> "최근 ${historyWith.first()} 님과 나눈 내용 정리해 드릴까요?"
            hasHistory -> "관련 기록 정리해 드릴까요?"
            people.isNotEmpty() -> "${people.first()} 님에 대해 저장된 정보를 확인해 드릴까요?"
            else -> "관련해 저장된 정보를 확인해 드릴까요?"
        }
        return "오늘 ${clock(item.beginMs)}에 '${item.title}' 일정이 있으시네요. $offer"
    }

    /** "11월 17일(화), 3일 뒤에 '한빛정밀 입찰 서류 제출' 일정이 있어요. 관련 기록 모아 드릴까요?" */
    fun deadlineMessage(item: CalendarItem, nowMs: Long): String {
        val date = localDate(item)
        val days = ChronoUnit.DAYS.between(today(nowMs), date)
        val whenText = if (days == 1L) "내일 ${dateLabel(date)}" else "${dateLabel(date)}, ${days}일 뒤"
        val time = if (item.allDay) "" else " ${clock(item.beginMs)}"
        return "$whenText$time '${item.title}' 일정이 있어요. 관련 기록 모아 드릴까요?"
    }

    /**
     * What a deadline offer opens to: the stored people, meetings and notes that name the deadline's company
     * or other distinctive words, each with its date, and the next steps Jarvis can already take.
     */
    fun deadlineDetail(item: CalendarItem, people: List<PersonHit>, meetings: List<MeetingHit>, memories: List<MemoryHit>): String {
        val words = deadlineWords(item)
        val lines = mutableListOf("${dateLabel(localDate(item))} ${item.title}")
        // Search is by meaning, so a hit must be checked here. A person counts when a title word is their
        // name or company. A meeting or note then counts when it names one of those people or companies,
        // or, with no such anchor, every distinctive title word: "견적" alone would pull in another company.
        val persons = people.filter { p -> words.any { w -> w in p.allNames || (p.org.isNotBlank() && p.org.contains(w, ignoreCase = true)) } }
            .distinctBy { listOf(it.name, it.org) }.take(3)
        val anchors = (persons.flatMap { it.allNames } + persons.map { it.org }).filter { it.length >= 2 }.distinct()
        fun about(vararg texts: String) =
            anchors.any { a -> texts.any { it.contains(a, ignoreCase = true) } } ||
                (words.isNotEmpty() && words.all { w -> texts.any { it.contains(w, ignoreCase = true) } })
        val pastMeetings = meetings.filter { about(it.title, it.summary) }.sortedByDescending { it.startedAt }.take(3)
        val notes = memories.filter { about(it.text, it.peopleText) }.take(3)
        persons.forEach { p -> lines += "사람: ${listOf(p.org, p.role, p.name).filter { it.isNotBlank() }.joinToString(" ")}" }
        pastMeetings.forEach { m ->
            lines += "회의 ${shortDate(m.startedAt)}: ${m.title}" +
                (m.summary.takeIf { it.isNotBlank() }?.let { " / ${it.take(120)}" } ?: "") +
                (if (m.actionItems.isNotEmpty()) " / 할 일: ${m.actionItems.joinToString(", ")}" else "")
        }
        notes.forEach { n -> lines += "기록 ${shortDate(n.capturedAt)}: ${n.text.take(120)}" }
        if (persons.isEmpty() && pastMeetings.isEmpty() && notes.isEmpty()) lines += "관련해서 저장된 기록은 아직 없어요."
        lines += "Jarvis에게 \"제출 전날 서류 준비 일정 잡아 줘\"라고 말하면 캘린더에 넣어 드려요."
        return lines.joinToString("\n")
    }

    /** Words to search a deadline with: the title minus the deadline words and short or generic pieces. */
    fun deadlineWords(item: CalendarItem): List<String> = MeetingBriefing.titleWords(item.asMeeting())
        .map { w -> DEADLINE_WORDS.fold(w) { acc, d -> acc.replace(d, "") }.removeSuffix("일").trim() }
        .filter { it.length >= 2 }
        .distinct()

    private fun shortDate(iso: String): String = runCatching {
        val t = runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrElse { Instant.parse(iso) }
        val d = t.atZone(KST)
        "${d.monthValue}월 ${d.dayOfMonth}일"
    }.getOrDefault("날짜 모름")

    /** Midnight KST [days] from today, for the calendar query window. */
    fun windowEnd(nowMs: Long, days: Long = LOOKAHEAD_DAYS): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), KST).toLocalDate().plusDays(days + 1)
            .atStartOfDay(KST).toInstant().toEpochMilli()
}
