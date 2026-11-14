package com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingBriefingTest {
    private val now = java.time.Instant.parse("2026-11-09T05:00:00Z").toEpochMilli()
    private val meeting = UpcomingMeeting("e1:1", "한빛산업 견적 미팅", now + 10 * 60_000L, attendees = listOf("홍길동", "me@example.com"))

    @Test
    fun dueOnlyFiveToTwelveMinutesAhead() {
        assertTrue(MeetingBriefing.isDue(meeting, now))
        assertFalse(MeetingBriefing.isDue(meeting.copy(beginMs = now + 14 * 60_000L), now))
        assertFalse(MeetingBriefing.isDue(meeting.copy(beginMs = now + 3 * 60_000L), now))
    }

    @Test
    fun keysAreNamesAndDistinctTitleWords() {
        assertEquals(listOf("홍길동", "한빛산업", "견적"), MeetingBriefing.keys(meeting))
        assertEquals(listOf("홍길동"), MeetingBriefing.keys(meeting.copy(title = "홍길동님과 미팅", attendees = emptyList())))
    }

    @Test
    fun keepsOnlyRecordsThatNameThePeopleAndShowsTheirDates() {
        val brief = MeetingBriefing.compose(
            meeting,
            people = listOf(PersonHit("홍길동", "한빛산업", "구매팀장", lastMetAt = "2026-10-02T05:00:00Z"), PersonHit("김철수", "다른회사")),
            meetings = listOf(
                MeetingHit("한빛산업 1차 미팅", "2026-10-02T05:00:00Z", "견적 조건을 논의했다. 다음 단계는 샘플.", listOf("견적서 보내기")),
                MeetingHit("주간 회의", "2026-10-20T01:00:00Z", "내부 일정 공유."),
            ),
            memories = emptyList(),
            nowMs = now,
        )
        assertTrue(brief.spoken.startsWith("10분 뒤에 한빛산업 견적 미팅 일정이 있어요."))
        assertTrue(brief.spoken.contains("상대는 한빛산업 구매팀장 홍길동 님이에요. 마지막으로 만난 날은 10월 2일이에요."))
        assertTrue(brief.spoken.contains("10월 2일 회의에서는 견적 조건을 논의했다."))
        assertTrue(brief.spoken.contains("견적서 보내기"))
        assertFalse(brief.spoken.contains("김철수"))      // semantic hit that never names anyone in the meeting
        val colleague = MeetingBriefing.compose(meeting, listOf(PersonHit("김철수", "한빛산업", "개발팀장")), emptyList(), emptyList(), now)
        assertFalse(colleague.spoken.contains("김철수"))  // same company, not invited
        assertFalse(brief.spoken.contains("주간 회의"))
    }

    @Test
    fun saysSoWhenNothingMatchesAndWarnsAboutSameNames() {
        val empty = MeetingBriefing.compose(meeting, emptyList(), emptyList(), listOf(MemoryHit("점심 메뉴 기록")), now)
        assertTrue(empty.spoken.endsWith("이 일정과 관련된 지난 기록은 찾지 못했어요."))
        val twins = MeetingBriefing.compose(meeting,
            listOf(PersonHit("홍길동", "한빛산업"), PersonHit("홍길동", "다른상사")), emptyList(), emptyList(), now)
        // The title names 한빛산업, so that one is used.
        assertTrue(twins.spoken.contains("상대는 한빛산업 홍길동 님이에요."))
        val unresolved = MeetingBriefing.compose(meeting.copy(title = "견적 미팅"),
            listOf(PersonHit("홍길동", "가나상사"), PersonHit("홍길동", "다른상사")),
            listOf(MeetingHit("다른상사 정산", "2026-10-01T01:00:00Z", "다른상사 미수금 정리.")), emptyList(), now)
        assertTrue(unresolved.spoken.contains("홍길동 님은 같은 이름이 여러 명 저장돼 있어서 누구인지 확인하지 못했어요."))
        assertFalse(unresolved.spoken.contains("다른상사"))
        val alias = MeetingBriefing.compose(meeting.copy(title = "박부장 미팅", attendees = emptyList()),
            listOf(PersonHit("박민수", "한빛산업", aliases = listOf("박부장"))), emptyList(), emptyList(), now)
        assertTrue(alias.spoken.contains("박민수"))
    }

    @Test
    fun unsettledNameInTheTitleDoesNotPullInEitherPersonsHistory() {
        val brief = MeetingBriefing.compose(UpcomingMeeting("e9:1", "김민수 미팅", now + 10 * 60_000L),
            listOf(PersonHit("김민수", "가나상사"), PersonHit("김민수", "다른상사")),
            listOf(MeetingHit("김민수 예산 협의", "2026-10-01T01:00:00Z", "다른상사 김민수와 예산 3억 논의.")), emptyList(), now)
        assertTrue(brief.spoken.contains("김민수 님은 같은 이름이 여러 명 저장돼 있어서"))
        assertFalse(brief.spoken.contains("예산"))
        assertFalse(brief.hasRecords)
    }

    @Test
    fun sharedTopicWordAloneDoesNotLinkAnotherCompanysMeeting() {
        val brief = MeetingBriefing.compose(meeting, emptyList(),
            listOf(MeetingHit("다른회사 견적 회의", "2026-10-05T01:00:00Z", "다른회사는 견적을 3억으로 받았다.")), emptyList(), now)
        assertFalse(brief.spoken.contains("다른회사"))
        // With no attendees, every distinctive title word must appear.
        val titleOnly = meeting.copy(attendees = emptyList())
        val linked = MeetingBriefing.compose(titleOnly, emptyList(),
            listOf(MeetingHit("한빛산업 견적 검토", "2026-10-05T01:00:00Z", "예산을 1.5억원으로 정했다. 납기는 다음 달.")), emptyList(), now)
        assertTrue(linked.spoken.contains("10월 5일 회의에서는 예산을 1.5억원으로 정했다."))
    }

    @Test
    fun neverSpeaksTheWakeWordButKeepsTheNotificationText() {
        val brief = MeetingBriefing.compose(meeting.copy(title = "자비스테크 시연 미팅"), emptyList(), emptyList(), emptyList(), now)
        assertFalse(brief.spoken.contains("자비스"))
        assertTrue(brief.detail.contains("자비스테크 시연 미팅"))
    }

    @Test
    fun scheduledMeetingsAreNotPastMeetings() {
        val brief = MeetingBriefing.compose(meeting, listOf(PersonHit("홍길동", "한빛산업")),
            listOf(MeetingHit("한빛산업 후속 미팅", "2099-01-01T01:00:00Z", "계약을 마무리한다.")), emptyList(), now)
        assertFalse(brief.spoken.contains("계약"))
    }

    @Test
    fun briefsEachOccurrenceOnce() {
        val dedupe = BriefingDedupe(keepMs = 1_000L)
        assertTrue(dedupe.claim("a", 0L))
        assertFalse(dedupe.claim("a", 500L))
        assertTrue(dedupe.claim("b", 500L))
        assertTrue(dedupe.claim("a", 2_000L)) // forgotten after keepMs
    }
}
