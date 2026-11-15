package com.meta.wearable.dat.externalsampleapps.cameraaccess.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Load and save errors on 기억 show Korean, never the network library's English text. */
class RecordErrorsTest {
    @Test fun networkFailureBecomesKoreanSentence() {
        val text = RecordErrors.text(java.net.ConnectException("Failed to connect to /10.0.2.2:8001"), "기억을 불러오지 못했어요")
        assertEquals("서버에 연결하지 못해 기억을 불러오지 못했어요. 인터넷 연결과 설정의 서버 주소를 확인해 주세요.", text)
    }

    @Test fun englishServerErrorIsReplaced() {
        val text = RecordErrors.text(IllegalStateException("HTTP 500: Internal Server Error"), "회의를 지우지 못했어요")
        assertEquals("회의를 지우지 못했어요. 잠시 뒤 다시 시도해 주세요.", text)
    }

    @Test fun koreanServerMessagePassesThrough() {
        val text = RecordErrors.text(IllegalStateException("이 기억은 이미 지워졌어요."), "기억을 지우지 못했어요")
        assertTrue(text.startsWith("이 기억은"))
    }
}
