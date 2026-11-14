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

enum class OfferKind { MEETING, DEADLINE, MAIL, MORNING }

/** A business mail the server picked from the inbox, with what it gathered for it. Nothing of it is kept on the server. */
data class MailCandidate(val id: String, val subject: String, val sender: String, val dateMs: Long, val message: String,
    val prep: List<String>)

/** A follow-up from a recent meeting, for the morning briefing. */
data class FollowupItem(val task: String, val dueText: String, val meetingTitle: String, val meetingDate: String)

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

    /** The label of an offer in the list, the notification title and the sheet. */
    fun kindLabel(kind: OfferKind) = when (kind) {
        OfferKind.MEETING -> "오늘 미팅"
        OfferKind.DEADLINE -> "다가오는 마감"
        OfferKind.MAIL -> "새 메일"
        OfferKind.MORNING -> "아침 브리핑"
    }

    /** The notification button. */
    fun actionLabel(kind: OfferKind) = when (kind) {
        OfferKind.MEETING -> "정리해 줘"
        OfferKind.DEADLINE -> "모아 줘"
        OfferKind.MAIL -> "자료 보기"
        OfferKind.MORNING -> "자세히 보기"
    }

    // ─── 새 메일 ───
    fun mailKey(id: String) = "mail:$id"

    /** A mail offer: the server's sentence, its prep lines, under a header naming who sent it and when. */
    fun mailOffer(mail: MailCandidate, nowMs: Long): ProactiveOffer {
        val head = "${dateLabel(Instant.ofEpochMilli(mail.dateMs).atZone(KST).toLocalDate())} ${clock(mail.dateMs)} " +
            (mail.sender.ifBlank { "보낸 사람 모름" }) + " 님이 보낸 메일"
        return ProactiveOffer("mail-${mail.id}", OfferKind.MAIL, mail.subject.ifBlank { "(제목 없음)" }, mail.message,
            (listOf(head) + mail.prep).joinToString("\n"), nowMs, mail.dateMs)
    }

    // ─── 아침 브리핑 ───
    const val MORNING_EARLIEST_MINUTE = QUIET_UNTIL_HOUR * 60
    /** After this hour a missed morning briefing is no longer sent; the day has started. */
    const val MORNING_LATEST_HOUR = 12

    fun morningKey(nowMs: Long) = "morning:${today(nowMs)}"

    /** True from the chosen time (never before 08:00) until noon, Korean time. */
    fun morningDue(nowMs: Long, minuteOfDay: Int): Boolean {
        val t = Instant.ofEpochMilli(nowMs).atZone(KST)
        val minute = t.hour * 60 + t.minute
        return minute >= maxOf(minuteOfDay, MORNING_EARLIEST_MINUTE) && t.hour < MORNING_LATEST_HOUR
    }

    /** When the overnight mail count starts: 21:00 KST the evening before. */
    fun overnightStart(nowMs: Long): Long =
        today(nowMs).minusDays(1).atTime(QUIET_FROM_HOUR, 0).atZone(KST).toInstant().toEpochMilli()

    /** "오늘 일정 2개, 다가오는 마감 1개, 챙길 할 일 3개가 있어요. 밤사이 업무 메일 2통도 왔어요." [mailCount] null: Gmail not connected. */
    fun morningMessage(todayCount: Int, deadlineCount: Int, followupCount: Int, mailCount: Int?): String {
        val parts = listOfNotNull(
            todayCount.takeIf { it > 0 }?.let { "오늘 일정 ${it}개" },
            deadlineCount.takeIf { it > 0 }?.let { "다가오는 마감 ${it}개" },
            followupCount.takeIf { it > 0 }?.let { "챙길 할 일 ${it}개" },
        )
        val first = if (parts.isEmpty()) "오늘은 등록된 일정이나 마감, 챙길 할 일이 없어요."
            else parts.joinToString(", ") + "가 있어요."
        val mail = if (mailCount != null && mailCount > 0) " 밤사이 업무 메일 ${mailCount}통도 왔어요." else ""
        return first + mail
    }

    /**
     * The morning sheet: today's events, deadlines in the next [DEADLINE_LEAD_DAYS] days, recent follow-ups
     * and the overnight mail count. [items] may hold other days; only today and the deadline window are used.
     */
    fun morningDetail(items: List<CalendarItem>, followups: List<FollowupItem>, mails: List<MailCandidate>?, nowMs: Long,
        mailFailed: Boolean = false): String {
        val day = today(nowMs)
        val lines = mutableListOf("${dateLabel(day)} 아침 브리핑")
        morningToday(items, nowMs).forEach { item ->
            lines += "오늘 일정: " + (if (item.allDay) "하루 종일" else clock(item.beginMs)) + " ${item.title}" +
                (if (item.location.isNotBlank()) " (${item.location})" else "")
        }
        morningDeadlines(items, nowMs).forEach { item -> lines += "다가오는 마감: ${dateLabel(localDate(item))} ${item.title}" }
        followups.take(5).forEach { f ->
            lines += "챙길 할 일: ${f.task}" + (if (f.dueText.isNotBlank()) " (${f.dueText})" else "") +
                (if (f.meetingTitle.isNotBlank()) " / ${f.meetingDate} ${f.meetingTitle}" else "")
        }
        when {
            mailFailed -> lines += "밤사이 메일: 지금은 메일을 확인하지 못했어요."
            mails == null -> lines += "밤사이 메일: 지메일을 연결하면 밤사이 온 업무 메일도 알려 드려요."
            mails.isEmpty() -> lines += "밤사이 메일: 새로 온 업무 메일은 없어요."
            else -> mails.take(3).forEach { m -> lines += "밤사이 메일: ${m.sender.ifBlank { "보낸 사람 모름" }}, ${m.subject}" }
        }
        if (lines.size == 1) lines += "오늘은 챙길 일정이 없어요."
        return lines.joinToString("\n")
    }

    /** Today's events (KST), deadlines included, timed ones that are not over yet, in time order. */
    fun morningToday(items: List<CalendarItem>, nowMs: Long): List<CalendarItem> {
        val day = today(nowMs)
        return items.filter { localDate(it) == day }.sortedBy { if (it.allDay) 0L else it.beginMs }
    }

    /** Deadline events from tomorrow up to [DEADLINE_LEAD_DAYS] days ahead. */
    fun morningDeadlines(items: List<CalendarItem>, nowMs: Long): List<CalendarItem> {
        val day = today(nowMs)
        return items.filter { isDeadline(it.title) && ChronoUnit.DAYS.between(day, localDate(it)) in 1L..DEADLINE_LEAD_DAYS }
            .sortedBy { it.beginMs }
    }

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
        // The morning briefing is its own once-a-day notice and does not use up the day's offers.
        return claimed.filterKeys { !it.startsWith("morning:") }.values
            .count { Instant.ofEpochMilli(it).atZone(KST).toLocalDate() == day }
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
    fun meetingMessage(item: CalendarItem, people: List<String>, hasHistory: Boolean, historyWith: List<String> = emptyList(),
        mentioned: List<String> = emptyList()): String {
        val offer = when {
            historyWith.isNotEmpty() -> "최근 ${historyWith.first()} 님과 나눈 내용 정리해 드릴까요?"
            mentioned.isNotEmpty() -> "${mentioned.first()} 님 관련 기록 정리해 드릴까요?"
            hasHistory -> "관련 기록 정리해 드릴까요?"
            people.isNotEmpty() -> "${people.first()} 님에 대해 저장된 정보를 확인해 드릴까요?"
            else -> "관련해 저장된 정보를 확인해 드릴까요?"
        }
        return "오늘 ${clock(item.beginMs)}에 '${item.title}' 일정이 있으시네요. $offer"
    }

    /**
     * "11월 17일(화)에 '한빛정밀 입찰 서류 제출' 일정이 있어요. 관련 기록 모아 드릴까요?" The offer is kept and
     * shown again later, so it names the date only; "3일 뒤" would be wrong by the next day.
     */
    @Suppress("UNUSED_PARAMETER")
    fun deadlineMessage(item: CalendarItem, nowMs: Long): String {
        val whenText = dateLabel(localDate(item)) + if (item.allDay) "" else " ${clock(item.beginMs)}"
        return "${whenText}에 '${item.title}' 일정이 있어요. 관련 기록 모아 드릴까요?"
    }

    /**
     * What a deadline offer opens to: the stored people, meetings and notes that name the deadline's company
     * or other distinctive words, each with its date, and the next steps Jarvis can already take.
     */
    fun deadlineDetail(item: CalendarItem, people: List<PersonHit>, meetings: List<MeetingHit>, memories: List<MemoryHit>,
        nowMs: Long? = null): String {
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
        // The detail is kept and may be opened later, so it never names a fixed preparation time that could
        // already be past; a new conversation does not know this offer, so the event's name goes in the example.
        lines += "준비할 날짜와 시각을 정해 Jarvis에게 \"${item.title} 준비 일정 잡아 줘\"라고 함께 말하면 캘린더에 넣어 드려요."
        return lines.joinToString("\n")
    }

    /** Words to search a deadline with: the title minus the deadline words and short or generic pieces. */
    fun deadlineWords(item: CalendarItem): List<String> = MeetingBriefing.titleWords(item.asMeeting())
        // "마감일" loses "마감" and leaves "일", which the length check drops; a name ending in 일 (제일) stays.
        .map { w -> DEADLINE_WORDS.fold(w) { acc, d -> acc.replace(d, "") }.trim() }
        .filter { it.length >= 2 }
        .distinct()

    private fun shortDate(iso: String): String = runCatching {
        val t = runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrElse { Instant.parse(iso) }
        val d = t.atZone(KST)
        "${d.monthValue}월 ${d.dayOfMonth}일"
    }.getOrDefault("날짜 모름")

    /**
     * When a kept offer stops making sense: a meeting offer ("오늘 오후 2시") two hours after the meeting
     * starts, a deadline offer at the end of its day (an all-day item starts at midnight UTC, 09:00 KST).
     */
    fun expiresAt(offer: ProactiveOffer): Long {
        if (offer.kind == OfferKind.MEETING) return offer.eventBeginMs + 2 * 3_600_000L
        // A mail offer stays for two days after the mail arrived; the morning briefing until the end of its day.
        if (offer.kind == OfferKind.MAIL) return offer.eventBeginMs + 2 * 24 * 3_600_000L
        // A deadline lasts until midnight KST at the end of its day. All-day items are stored at midnight UTC,
        // which is 09:00 KST of the same date, so the KST date of the start is the deadline's date either way.
        val day = Instant.ofEpochMilli(offer.eventBeginMs).atZone(KST).toLocalDate()
        return day.plusDays(1).atStartOfDay(KST).toInstant().toEpochMilli()
    }

    fun isLive(offer: ProactiveOffer, nowMs: Long): Boolean = nowMs < expiresAt(offer)

    /** Midnight KST [days] from today, for the calendar query window. */
    fun windowEnd(nowMs: Long, days: Long = LOOKAHEAD_DAYS): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), KST).toLocalDate().plusDays(days + 1)
            .atStartOfDay(KST).toInstant().toEpochMilli()
}
