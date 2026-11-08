package com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingFollowupsTest {
    // Monday 2026-11-09, 15:00 KST
    private val start = ZonedDateTime.of(2026, 11, 9, 15, 0, 0, 0, ZoneId.of("Asia/Seoul"))
    private fun due(text: String, at: String = "") = MeetingFollowups.dueDate(MeetingFollowup("t", dueText = text, dueAt = at), start)

    @Test
    fun resolvesOnlyUnambiguousDueDates() {
        assertEquals(LocalDate.of(2026, 11, 9), due("오늘"))
        assertEquals(LocalDate.of(2026, 11, 10), due("내일까지"))
        assertEquals(LocalDate.of(2026, 11, 13), due("금요일"))
        assertEquals(LocalDate.of(2026, 11, 13), due("이번 주 금요일"))
        assertEquals(LocalDate.of(2026, 11, 20), due("다음 주 금요일"))
        assertEquals(LocalDate.of(2026, 11, 16), due("월요일"))          // today is Monday: the next one
        assertEquals(LocalDate.of(2026, 11, 20), due("11월 20일"))
        assertEquals(LocalDate.of(2027, 1, 5), due("1월 5일"))            // said in November: next year
        assertEquals(LocalDate.of(2026, 11, 14), due("", "2026-11-14"))
        assertEquals(LocalDate.of(2027, 11, 20), due("2027년 11월 20일까지")) // a stated year is kept
        assertNull(due("다다음 주 금요일"))   // unsupported qualifiers stay open
        assertNull(due("다음 달 금요일"))
        assertNull(due("다음 주"))   // no day named: the user picks
        assertNull(due("내일 전에"))       // "before" and "around" are not "by"
        assertNull(due("금요일쯤"))
        assertNull(due("이번 달"))
        assertNull(due(""))
    }

    @Test
    fun usesServerFollowupsAndFallsBackToActionItems() {
        val json = JSONArray().put(JSONObject().put("task", "견적서 보내기").put("owner", "김대리")
            .put("due_text", "금요일").put("evidence", "금요일까지 견적서 보내드릴게요"))
            .put(JSONObject().put("task", " "))
        val parsed = MeetingFollowups.parse(json, listOf("무시됨"))
        assertEquals(1, parsed.size)
        assertEquals("김대리", parsed[0].owner)
        assertEquals(listOf("샘플 전달"), MeetingFollowups.parse(null, listOf("샘플 전달", " ")).map { it.task })
    }

    @Test
    fun draftListsDecisionsAndTasksWithDueDates() {
        val text = MeetingFollowups.draftMessage(listOf("다음 달 시범 도입"),
            listOf(MeetingFollowup("견적서 보내기", owner = "김대리", dueText = "금요일"), MeetingFollowup("자료 공유")), start)
        assertTrue(text.contains("- 다음 달 시범 도입"))
        assertTrue(text.contains("- 견적서 보내기 (김대리, 11월 13일까지)"))
        assertTrue(text.contains("- 자료 공유\n"))
        val later = MeetingFollowups.draftMessage(emptyList(), listOf(MeetingFollowup("계약", dueText = "2027년 11월 20일")), start)
        assertTrue(later.contains("2027년 11월 20일까지"))
    }
}
