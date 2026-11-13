package com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactivePlannerTest {
    private val kst = ZoneId.of("Asia/Seoul")
    private fun kst(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, kst).toInstant().toEpochMilli()
    /** How the phone stores an all-day event: midnight UTC of its date. */
    private fun allDay(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    // Friday 2026-11-13, 10:00 KST.
    private val now = kst(2026, 11, 13, 10)

    @Test
    fun meetingOfferedTwentyMinutesToTwoHoursAheadSameDay() {
        val m = CalendarItem("e1:1", "한빛정밀 견적 미팅", kst(2026, 11, 13, 12), allDay = false)
        assertTrue(ProactivePlanner.meetingDue(m, now))
        assertFalse(ProactivePlanner.meetingDue(m.copy(beginMs = kst(2026, 11, 13, 12, 30)), now))
        assertFalse(ProactivePlanner.meetingDue(m.copy(beginMs = kst(2026, 11, 13, 10, 10)), now))
        // A deadline title is a deadline, not a meeting; all-day items are never meeting offers.
        assertFalse(ProactivePlanner.meetingDue(m.copy(title = "입찰 서류 제출"), now))
        assertFalse(ProactivePlanner.meetingDue(m.copy(allDay = true), now))
    }

    @Test
    fun meetingAfterMidnightIsNotToday() {
        val late = kst(2026, 11, 13, 23)
        val m = CalendarItem("e2:1", "야간 통화", kst(2026, 11, 14, 0, 30), allDay = false)
        assertFalse(ProactivePlanner.meetingDue(m, late))
    }

    @Test
    fun deadlineKeywords() {
        listOf("대성테크 입찰 서류 제출", "보고서 마감", "납기 확인", "지원 신청", "접수 기한").forEach {
            assertTrue(it, ProactivePlanner.isDeadline(it))
        }
        assertFalse(ProactivePlanner.isDeadline("한빛정밀 견적 미팅"))
    }

    @Test
    fun allDayDateIsReadInUtcNotKorea() {
        // Midnight UTC of the 16th is 09:00 KST of the 16th; it must stay the 16th, not move.
        val item = CalendarItem("d:1", "입찰 서류 제출", allDay(2026, 11, 16), allDay = true)
        assertEquals(LocalDate.of(2026, 11, 16), ProactivePlanner.localDate(item))
        // A timed event at 08:00 KST on the 17th is 23:00 UTC on the 16th; its Korean date is the 17th.
        val timed = CalendarItem("d:2", "서류 제출", kst(2026, 11, 17, 8), allDay = false)
        assertEquals(LocalDate.of(2026, 11, 17), ProactivePlanner.localDate(timed))
    }

    @Test
    fun deadlineStagesThreeDaysAheadAndTheDayBefore() {
        val d = CalendarItem("d:1", "대성테크 입찰 서류 제출", allDay(2026, 11, 16), allDay = true)
        assertEquals(DeadlineStage.EARLY, ProactivePlanner.deadlineStage(d, now)) // 3 days
        assertEquals(DeadlineStage.EARLY, ProactivePlanner.deadlineStage(d, kst(2026, 11, 14, 10))) // 2 days
        assertEquals(DeadlineStage.EVE, ProactivePlanner.deadlineStage(d, kst(2026, 11, 15, 9)))
        assertNull(ProactivePlanner.deadlineStage(d, kst(2026, 11, 16, 9))) // the day itself
        assertNull(ProactivePlanner.deadlineStage(d.copy(beginMs = allDay(2026, 11, 20)), now)) // 7 days
        assertNull(ProactivePlanner.deadlineStage(d.copy(title = "점심 약속"), now))
    }

    @Test
    fun quietHoursAndDailyCap() {
        assertTrue(ProactivePlanner.withinHours(kst(2026, 11, 13, 8)))
        assertTrue(ProactivePlanner.withinHours(kst(2026, 11, 13, 20, 59)))
        assertFalse(ProactivePlanner.withinHours(kst(2026, 11, 13, 21)))
        assertFalse(ProactivePlanner.withinHours(kst(2026, 11, 13, 7, 59)))
        val claimed = mapOf("a" to kst(2026, 11, 13, 8), "b" to kst(2026, 11, 13, 9), "c" to kst(2026, 11, 12, 20))
        assertEquals(2, ProactivePlanner.sentToday(claimed, now))
    }

    @Test
    fun dedupeKeysSeparateMeetingAndEachDeadlineStage() {
        val d = CalendarItem("9:100", "입찰 서류 제출", allDay(2026, 11, 16), allDay = true)
        val keys = setOf(ProactivePlanner.meetingKey(d), ProactivePlanner.deadlineKey(d, DeadlineStage.EARLY),
            ProactivePlanner.deadlineKey(d, DeadlineStage.EVE))
        assertEquals(3, keys.size)
    }

    @Test
    fun wordingUsesKoreanClockAndAbsoluteDate() {
        val m = CalendarItem("e1:1", "한빛정밀 견적 미팅", kst(2026, 11, 13, 14), allDay = false)
        assertEquals("오늘 오후 2시에 '한빛정밀 견적 미팅' 일정이 있으시네요. 최근 김민수 님과 나눈 내용 정리해 드릴까요?",
            ProactivePlanner.meetingMessage(m, listOf("김민수"), hasHistory = true, historyWith = listOf("김민수")))
        // History that never names 김민수 is "관련 기록", not a conversation with him.
        assertEquals("오늘 오후 2시에 '한빛정밀 견적 미팅' 일정이 있으시네요. 관련 기록 정리해 드릴까요?",
            ProactivePlanner.meetingMessage(m, listOf("김민수"), hasHistory = true, historyWith = emptyList()))
        // A saved contact without any past meeting or note is not offered as "나눈 내용".
        assertEquals("오늘 오후 2시에 '한빛정밀 견적 미팅' 일정이 있으시네요. 김민수 님에 대해 저장된 정보를 확인해 드릴까요?",
            ProactivePlanner.meetingMessage(m, listOf("김민수"), hasHistory = false))
        assertEquals("오전 10시 30분", ProactivePlanner.clock(kst(2026, 11, 13, 10, 30)))
        assertEquals("오후 12시", ProactivePlanner.clock(kst(2026, 11, 13, 12)))
        val d = CalendarItem("d:1", "대성테크 입찰 서류 제출", allDay(2026, 11, 16), allDay = true)
        // Kept and shown again later, so only the absolute date: the same text three days or one day ahead.
        assertEquals("11월 16일(월)에 '대성테크 입찰 서류 제출' 일정이 있어요. 관련 기록 모아 드릴까요?",
            ProactivePlanner.deadlineMessage(d, now))
        assertEquals(ProactivePlanner.deadlineMessage(d, now), ProactivePlanner.deadlineMessage(d, kst(2026, 11, 15, 9)))
        assertEquals("11월 17일(화) 오후 3시에 '서류 제출' 일정이 있어요. 관련 기록 모아 드릴까요?",
            ProactivePlanner.deadlineMessage(CalendarItem("d:3", "서류 제출", kst(2026, 11, 17, 15), allDay = false), now))
    }

    @Test
    fun deadlineDetailKeepsOnlyRecordsAboutTheCompany() {
        val d = CalendarItem("d:1", "대성테크 입찰 서류 제출", allDay(2026, 11, 16), allDay = true)
        assertEquals(listOf("대성테크"), ProactivePlanner.deadlineWords(d))
        // A company name ending in 일 is kept; "마감일" leaves nothing.
        assertEquals(listOf("제일"), ProactivePlanner.deadlineWords(d.copy(title = "제일 입찰 서류 제출")))
        assertEquals(listOf("보고서"), ProactivePlanner.deadlineWords(d.copy(title = "보고서 마감일")))
        val detail = ProactivePlanner.deadlineDetail(d,
            people = listOf(PersonHit("박지훈", "대성테크", "구매 과장"), PersonHit("김철수", "다른회사")),
            meetings = listOf(MeetingHit("대성테크 사양 회의", "2026-11-02T05:00:00Z", "샘플 두 대 요청")),
            memories = listOf(MemoryHit("다른 회사 메모")))
        assertTrue(detail.contains("대성테크 구매 과장 박지훈"))
        assertFalse(detail.contains("김철수"))
        assertTrue(detail.contains("회의 11월 2일: 대성테크 사양 회의"))
        assertFalse(detail.contains("다른 회사 메모"))
    }

    @Test
    fun aNoteThatOnlyNamesSomeoneIsNotAConversation() {
        val m = CalendarItem("e1:1", "한빛정밀 견적 미팅", kst(2026, 11, 13, 14), allDay = false)
        assertEquals("오늘 오후 2시에 '한빛정밀 견적 미팅' 일정이 있으시네요. 김민수 님 관련 기록 정리해 드릴까요?",
            ProactivePlanner.meetingMessage(m, listOf("김민수"), hasHistory = true, mentioned = listOf("김민수")))
    }

    @Test
    fun preparationExampleNeverNamesAFixedTime() {
        val d = CalendarItem("d:1", "대성테크 입찰 서류 제출", allDay(2026, 11, 16), allDay = true)
        val detail = ProactivePlanner.deadlineDetail(d, emptyList(), emptyList(), emptyList(), now)
        assertFalse(detail.contains("오전 9시에"))
        assertTrue(detail.contains("준비할 날짜와 시각을 정해 Jarvis에게 \"대성테크 입찰 서류 제출 준비 일정 잡아 줘\""))
    }

    @Test
    fun offersExpireAfterTheirEvent() {
        val meeting = ProactiveOffer("m", OfferKind.MEETING, "t", "", "", now, kst(2026, 11, 13, 14))
        assertTrue(ProactivePlanner.isLive(meeting, kst(2026, 11, 13, 15)))
        assertFalse(ProactivePlanner.isLive(meeting, kst(2026, 11, 13, 16, 1)))
        val deadline = ProactiveOffer("d", OfferKind.DEADLINE, "t", "", "", now, allDay(2026, 11, 16))
        assertTrue(ProactivePlanner.isLive(deadline, kst(2026, 11, 16, 23, 59)))
        assertFalse(ProactivePlanner.isLive(deadline, kst(2026, 11, 17, 0)))
        // A timed deadline also ends at midnight of its day, not 24 hours after it starts.
        val timed = ProactiveOffer("t", OfferKind.DEADLINE, "t", "", "", now, kst(2026, 11, 16, 15))
        assertFalse(ProactivePlanner.isLive(timed, kst(2026, 11, 17, 0, 30)))
    }

    @Test
    fun aGenericWordAloneDoesNotPullInAnotherCompany() {
        val d = CalendarItem("d:2", "대성테크 견적 제출", allDay(2026, 11, 16), allDay = true)
        val detail = ProactivePlanner.deadlineDetail(d, people = emptyList(),
            meetings = listOf(MeetingHit("다른회사 견적 회의", "2026-11-02T05:00:00Z", "단가 10% 인하")),
            memories = emptyList())
        assertFalse(detail.contains("다른회사"))
        assertTrue(detail.contains("저장된 기록은 아직 없어요"))
    }
}
