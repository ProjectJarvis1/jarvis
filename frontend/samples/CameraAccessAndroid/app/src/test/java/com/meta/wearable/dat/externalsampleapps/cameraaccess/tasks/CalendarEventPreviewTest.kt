package com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarEventPreviewTest {
    @Test
    fun showsTheRealDayTimeAndPlace() {
        val p = CalendarEventPreview.of("""{"title":"한빛정밀 미팅","start_at":"2026-11-20T16:00:00+09:00","end_at":"2026-11-20T17:30:00+09:00","location":"판교 본사"}""")
        assertEquals("한빛정밀 미팅", p.title)
        assertEquals("2026년 11월 20일 (금) 오후 4:00 ~ 오후 5:30", p.time)
        assertEquals("판교 본사", p.place)
    }

    @Test
    fun convertsToKoreaTimeAndNeverGuessesAnUnreadableTime() {
        // The clock time as said, whatever offset the model attached: the same reading the calendar write uses.
        assertEquals("2026년 11월 20일 (금) 오후 4:00", CalendarEventPreview.timeLabel("2026-11-20T16:00:00Z", null))
        assertEquals("2026년 11월 20일 (금) 오후 4:00", CalendarEventPreview.timeLabel("2026-11-20T16:00:00", null))
        assertEquals(java.time.Instant.parse("2026-11-20T07:00:00Z"),
            CalendarEventPreview.koreaWallTime("2026-11-20T16:00:00-05:00")?.toInstant())
        assertEquals("시각 확인 필요: 다음 주 금요일", CalendarEventPreview.timeLabel("다음 주 금요일", null))
        assertEquals("시각 정보 없음", CalendarEventPreview.timeLabel(null, null))
        // A wrong year from the model is visible before approval.
        assertEquals("2037년 11월 20일 (금) 오후 4:00", CalendarEventPreview.timeLabel("2037-11-20T16:00:00+09:00", null))
        assertEquals(listOf("제목 없는 일정", "시각 정보 없음"), CalendarEventPreview.lines("{}"))
    }
}
