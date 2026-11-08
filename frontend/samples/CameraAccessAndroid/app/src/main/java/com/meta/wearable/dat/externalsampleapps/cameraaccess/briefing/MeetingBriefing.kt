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

data class PersonHit(val name: String, val org: String = "", val role: String = "", val lastMetAt: String = "", val notes: String = "")
data class MeetingHit(val title: String, val startedAt: String, val summary: String = "", val actionItems: List<String> = emptyList())
data class MemoryHit(val text: String, val peopleText: String = "", val capturedAt: String = "")

/** What to say on the glasses, and the longer text for the phone notification. */
data class Brief(val spoken: String, val detail: String)

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

    private fun titleWords(meeting: UpcomingMeeting) = meeting.title.split(Regex("""[\s,/()\[\]·:|-]+"""))
        .map { it.trim().removeSuffix("님").removeSuffix("과").removeSuffix("와") }
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
        val persons = people.filter { p ->
            if (confirmed) names.any { it == p.name }
            else titleWords.any { w -> p.name == w || (p.org.isNotBlank() && p.org.contains(w, ignoreCase = true)) }
        }.distinctBy { listOf(it.name, it.org) }.take(3)
        val anchors = (names + persons.map { it.org }).filter { it.length >= 2 }.distinct()
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

        persons.groupBy { it.name }.filter { it.value.size > 1 }.keys.firstOrNull()?.let {
            spoken += "$it 님이 여러 명 저장돼 있어서 회사를 확인해 주세요."
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
        if (persons.isEmpty() && pastMeetings.isEmpty() && notes.isEmpty()) {
            spoken += "이 일정과 관련된 지난 기록은 찾지 못했어요."
            detail += "관련된 지난 기록 없음"
        }
        // The wake word in Jarvis's own voice would start a new conversation; the notification keeps the text as is.
        return Brief(spoken.joinToString(" ").replace("자비스", ""), detail.joinToString("\n"))
    }

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
