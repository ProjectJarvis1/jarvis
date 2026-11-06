package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertTrue
import org.junit.Test

class NowLineTest {
    @Test
    fun saysKoreanDateAndWeekday() {
        val line = GeminiConfig.nowLine(ZonedDateTime.of(2026, 11, 7, 3, 15, 0, 0, ZoneId.of("Asia/Seoul")))
        assertTrue(line, line.contains("2026-11-07 03:15 (토요일)"))
    }
}
