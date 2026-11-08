package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTrialTest {
    @Test
    fun normalLoopRestartsAtOnceAndFailuresBackOff() {
        assertEquals(WakeWordRetry.NORMAL_MS, WakeWordRetry.delayMs(null, 3))
        assertEquals(WakeWordRetry.NORMAL_MS, WakeWordRetry.delayMs(7, 3)) // nothing heard
        assertEquals(500L, WakeWordRetry.delayMs(8, 0))                    // busy, first time
        assertEquals(2_000L, WakeWordRetry.delayMs(8, 2))
        assertEquals(WakeWordRetry.MAX_MS, WakeWordRetry.delayMs(5, 40))
        assertEquals(WakeWordRetry.PERMISSION_MS, WakeWordRetry.delayMs(9, 0)) // background mic refused
    }

    @Test
    fun countsScreenOffDetectionsAndOnlyRealErrors() {
        val start = 1_700_000_000_000L
        val trial = WakeWordTrial()
            .started(start, 80)
            .started(start + 5_000, 70) // a second open keeps the first start
            .listened().listened()
            .detected(start + 60_000, screenOff = true)
            .detected(start + 120_000, screenOff = false)
            .failed(7, start).failed(9, start + 3_600_000).failed(9, start + 3_700_000)
        assertEquals(80, trial.batteryAtStart)
        val lines = trial.summary(start + 2 * 3_600_000, 74)
        assertTrue(lines.any { it.contains("호출 인식 2번, 그중 화면 꺼짐 1번") })
        assertTrue(lines.any { it.contains("시간당 약 3.0%") })
        val errorLine = lines.first { it.startsWith("듣기 오류") }
        assertTrue(errorLine.contains("9번 2회"))
        assertTrue(!errorLine.contains("7번")) // "nothing heard" is not an error
    }

    @Test
    fun emptyRecordSaysSo() {
        assertEquals(1, WakeWordTrial().summary(0L, 50).size)
    }
}
