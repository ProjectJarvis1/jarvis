package com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A calendar event about to start. [key] is unique per occurrence (event id + start), for dedupe. */
data class UpcomingMeeting(
    val key: String,
    val title: String,
    val beginMs: Long,
    val location: String = "",
    val attendees: List<String> = emptyList(),
)

data class PersonHit(
    val name: String,
    val org: String = "",
    val role: String = "",
    val lastMetAt: String = "",
    val notes: String = "",
    val aliases: List<String> = emptyList(),
) {
    /** The saved name and the other names the person goes by ("박부장"). */
    val allNames: List<String> get() = (listOf(name) + aliases).filter { it.isNotBlank() }
}
data class MeetingHit(val title: String, val startedAt: String, val summary: String = "", val actionItems: List<String> = emptyList())
data class MemoryHit(val text: String, val peopleText: String = "", val capturedAt: String = "")

/**
 * What to say on the glasses, and the longer text for the phone notification. [people] names the matched
 * people, and [hasRecords] is false when nothing stored was about this meeting.
 */
data class Brief(val spoken: String, val detail: String, val people: List<String> = emptyList(), val hasRecords: Boolean = true,
    val hasHistory: Boolean = true, val historyWith: List<String> = emptyList(), val mentioned: List<String> = emptyList(),
    val orgs: List<String> = emptyList(), val ambiguous: List<String> = emptyList())

/** A recent mail with someone in the meeting, as the server found it in Gmail ([date] is "2026-11-12 10:00", KST). */
data class MailHit(val sender: String, val subject: String, val date: String, val snippet: String = "")

/**
 * Builds the briefing a few minutes before a meeting from what Jarvis has stored about the people in it.
 * Server search is by meaning, so a hit only counts when an attendee's name, or a distinctive word of
 * the event title, appears in it literally; otherwise the brief says there is no record. Every fact
 * carries the date it was recorded, so the user can tell how fresh it is.
 */
object MeetingBriefing {
    const val LEAD_MIN_MS = 5 * 60_000L
    const val LEAD_MAX_MS = 12 * 60_000L

    private val KST = ZoneId.of("Asia/Seoul")
    private val DAY = DateTimeFormatter.ofPattern("M월 d일").withZone(KST)
    private val STOP_WORDS = setOf(
        "미팅", "회의", "통화", "점심", "저녁", "식사", "약속", "방문", "면담", "상담", "미팅룸", "온라인", "오프라인",
        "zoom", "meet", "call", "meeting", "teams", "with", "and", "the", "주간", "정기", "월간", "내부", "외부",
    )

    /** Briefed once when the start is 5 to 12 minutes away; the scheduler polls every minute. */
    fun isDue(meeting: UpcomingMeeting, nowMs: Long): Boolean = meeting.beginMs - nowMs in LEAD_MIN_MS..LEAD_MAX_MS

    /** Words to search the server with: attendee names, then distinctive title words. */
    fun keys(meeting: UpcomingMeeting): List<String> = (attendeeNames(meeting) + titleWords(meeting)).distinct()

    private fun attendeeNames(meeting: UpcomingMeeting) =
        meeting.attendees.map { it.trim().removeSuffix("님").trim() }.filter { it.length >= 2 && '@' !in it }.distinct()

    internal fun titleWords(meeting: UpcomingMeeting) = meeting.title.split(Regex("""[\s,/()\[\]·:|-]+"""))
        // "홍길동님과" -> "홍길동": the particle comes off before the honorific.
        .map { it.trim().removeSuffix("과").removeSuffix("와").removeSuffix("님") }
        .filter { it.length >= 2 && it.lowercase() !in STOP_WORDS && !it.all(Char::isDigit) }
        .distinct()

    fun compose(
        meeting: UpcomingMeeting,
        people: List<PersonHit>,
        meetings: List<MeetingHit>,
        memories: List<MemoryHit>,
        nowMs: Long,
    ): Brief {
        // Search is by meaning, so a hit must name someone in this meeting. A person counts when their
        // name is an attendee's, or a title word is their name or company. A past meeting or a note
        // counts when it names an attendee or one of those companies, or, failing that, every
        // distinctive title word: "견적" alone would pull in another company's quote meeting.
        val names = attendeeNames(meeting)
        val titleWords = titleWords(meeting)
        // With invited attendees, only they are "the other side"; a company search also returns their
        // colleagues, who are not stated as attending. Without attendees, people found through the title
        // are mentioned as unconfirmed.
        val confirmed = names.isNotEmpty()
        val found = people.filter { p ->
            if (confirmed) names.any { it in p.allNames }
            else titleWords.any { w -> w in p.allNames || (p.org.isNotBlank() && p.org.contains(w, ignoreCase = true)) }
        }.distinctBy { listOf(it.name, it.org) }
        // Two saved people with one name: keep the one whose company is in the title; if that does not
        // settle it, use neither, so another person's history is never read out as this one's.
        val ambiguous = mutableListOf<String>()
        val persons = found.groupBy { it.name }.flatMap { (name, same) ->
            if (same.size == 1) same
            else same.filter { p -> p.org.isNotBlank() && titleWords.any { p.org.contains(it, ignoreCase = true) } }
                .takeIf { it.size == 1 } ?: run { ambiguous += name; emptyList() }
        }.take(3)
        val anchors = (names - ambiguous.toSet() + persons.flatMap { it.allNames } + persons.map { it.org })
            .filter { it.length >= 2 }.distinct()
        fun about(vararg texts: String) =
            anchors.any { a -> texts.any { it.contains(a, ignoreCase = true) } } ||
                (titleWords.isNotEmpty() && titleWords.all { w -> texts.any { it.contains(w, ignoreCase = true) } })
        // Only meetings that already happened; a scheduled one is not "지난 회의".
        val pastMeetings = meetings.filter { about(it.title, it.summary) && (epochMs(it.startedAt)?.let { t -> t < nowMs } ?: false) }
            .sortedByDescending { it.startedAt }.take(2)
        val notes = memories.filter { about(it.text, it.peopleText) }.take(1)

        val minutes = ((meeting.beginMs - nowMs) / 60_000L).coerceAtLeast(1)
        val spoken = mutableListOf("${minutes}분 뒤에 ${meeting.title} 일정이 있어요.")
        val detail = mutableListOf("${minutes}분 뒤 ${meeting.title}" + if (meeting.location.isNotBlank()) " (${meeting.location})" else "")

        ambiguous.firstOrNull()?.let {
            spoken += "$it 님은 같은 이름이 여러 명 저장돼 있어서 누구인지 확인하지 못했어요."
            detail += "$it: 같은 이름이 여러 명 저장돼 있어 기록을 붙이지 않았어요."
        }
        persons.take(2).forEach { p ->
            val who = listOf(p.org, p.role).filter { it.isNotBlank() }.joinToString(" ")
            val met = day(p.lastMetAt)?.let { " 마지막으로 만난 날은 ${it}이에요." } ?: ""
            spoken += when {
                !confirmed -> "참석자는 확인되지 않았는데, 저장된 사람 중에 ${listOf(who, p.name).filter { it.isNotBlank() }.joinToString(" ")} 님이 있어요."
                who.isNotEmpty() -> "상대는 $who ${p.name} 님이에요."
                else -> "${p.name} 님 정보가 있어요."
            } + met
            detail += "${p.name}: ${who.ifEmpty { "소속 정보 없음" }}" +
                (day(p.lastMetAt)?.let { ", 마지막 만남 $it" } ?: "") + (p.notes.takeIf { it.isNotBlank() }?.let { ", 메모 ${it.take(80)}" } ?: "")
        }
        pastMeetings.forEach { m ->
            val date = day(m.startedAt) ?: "지난"
            val summary = firstSentence(m.summary)
            if (summary.isNotEmpty()) spoken += "$date 회의에서는 $summary"
            if (m.actionItems.isNotEmpty()) spoken += "그때 정한 할 일은 ${m.actionItems.take(2).joinToString(", ")}."
            detail += "$date 회의: ${m.summary.take(160)}" + if (m.actionItems.isNotEmpty()) " / 할 일: ${m.actionItems.joinToString(", ")}" else ""
        }
        notes.forEach { n ->
            val date = day(n.capturedAt)
            spoken += "${date ?: "예전"}에 저장한 기록도 있어요. ${firstSentence(n.text.take(80))}"
            detail += "${date ?: "날짜 모름"} 기록: ${n.text.take(160)}"
        }
        val hasRecords = persons.isNotEmpty() || pastMeetings.isNotEmpty() || notes.isNotEmpty()
        if (!hasRecords) {
            spoken += "이 일정과 관련된 지난 기록은 찾지 못했어요."
            detail += "관련된 지난 기록 없음"
        }
        // The wake word in Jarvis's own voice would start a new conversation; the notification keeps the text as is.
        return Brief(spoken.joinToString(" ").replace("자비스", ""), detail.joinToString("\n"),
            people = persons.map { it.name }, hasRecords = hasRecords,
            hasHistory = pastMeetings.isNotEmpty() || notes.isNotEmpty(),
            // "최근 ○○ 님과 나눈 내용" only for people a past meeting names; a note that merely names someone
            // ("김민수 님에게 보낼 제안서") is a record about them, not a conversation with them.
            historyWith = persons.filter { p ->
                val texts = pastMeetings.flatMap { listOf(it.title, it.summary) + it.actionItems }
                p.allNames.any { n -> texts.any { it.contains(n) } }
            }.map { it.name },
            mentioned = persons.filter { p ->
                val texts = notes.flatMap { listOf(it.text, it.peopleText) }
                p.allNames.any { n -> texts.any { it.contains(n) } }
            }.map { it.name },
            orgs = persons.map { it.org }.filter { it.isNotBlank() }.distinct(),
            ambiguous = ambiguous.toList())
    }

    /**
     * Adds recent mail with the meeting's people to a brief: every mail in the notification text, the latest
     * one read out by its subject only. Mail is someone else's writing, so nothing in it is said as an instruction.
     */
    fun withMail(brief: Brief, mails: List<MailHit>): Brief {
        if (mails.isEmpty()) return brief
        val lines = mails.take(3).map { m ->
            "메일 ${mailDay(m.date)} ${m.sender.ifBlank { "보낸 사람 모름" }}: ${m.subject.ifBlank { "(제목 없음)" }}" +
                (m.snippet.takeIf { it.isNotBlank() }?.let { " / ${it.take(100)}" } ?: "")
        }
        val latest = mails.first()
        val said = "최근 메일은 ${mailDay(latest.date)}에 ${latest.sender.ifBlank { "상대" }} 님이 보낸 '${latest.subject.take(40)}'이에요."
        // "관련된 지난 기록 없음" is no longer true once a mail is there.
        val detail = brief.detail.lines().filterNot { it == "관련된 지난 기록 없음" } + lines
        val spoken = brief.spoken.replace(" 이 일정과 관련된 지난 기록은 찾지 못했어요.", "")
        return brief.copy(spoken = "$spoken $said".replace("자비스", ""), detail = detail.joinToString("\n"),
            hasRecords = true, hasHistory = true)
    }

    /** "2026-11-12 10:00" -> "11월 12일". */
    internal fun mailDay(date: String): String =
        Regex("""^\d{4}-(\d{2})-(\d{2})""").find(date)?.let { "${it.groupValues[1].toInt()}월 ${it.groupValues[2].toInt()}일" } ?: "최근"

    /** Up to the first sentence end (". " or end of text; "1.5억" is not an end), at most about 90 characters. */
    private fun firstSentence(text: String): String {
        val t = text.trim().replace(Regex("""\s+"""), " ")
        if (t.isEmpty()) return ""
        val end = Regex("""[.!?。](?=\s|$)""").find(t)?.range?.last?.plus(1) ?: t.length
        var sentence = t.take(end)
        if (sentence.length > 90) sentence = sentence.take(90).substringBeforeLast(' ', sentence.take(90)) + "…"
        return if (sentence.last() in ".!?。…") sentence else "$sentence."
    }

    private fun epochMs(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

    private fun day(iso: String): String? {
        if (iso.isBlank()) return null
        return runCatching { DAY.format(OffsetDateTime.parse(iso).toInstant()) }.getOrNull()
            ?: runCatching { DAY.format(Instant.parse(iso)) }.getOrNull()
    }
}

/** Remembers which occurrences were briefed, so each meeting is briefed once even across app restarts. */
class BriefingDedupe(initial: Map<String, Long> = emptyMap(), private val keepMs: Long = 2 * 24 * 3_600_000L) {
    private val seen = initial.toMutableMap()

    /** True the first time [key] is claimed; old entries are dropped. */
    @Synchronized
    fun claim(key: String, nowMs: Long): Boolean {
        seen.entries.removeIf { nowMs - it.value > keepMs }
        if (key in seen) return false
        seen[key] = nowMs
        return true
    }

    @Synchronized
    fun contains(key: String, nowMs: Long): Boolean {
        seen.entries.removeIf { nowMs - it.value > keepMs }
        return key in seen
    }

    @Synchronized
    fun snapshot(): Map<String, Long> = seen.toMap()
}
