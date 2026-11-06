package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceApprovalTest {
    @Test
    fun plainYesApproves() {
        listOf("승인", "응", "네.", "응 저장해", "그래 저장해 줘", "맞아요").forEach { assertTrue(it, VoiceApproval.isApproval(it, 1_000)) }
    }

    @Test
    fun noCorrectionOrOldAnswerDoesNot() {
        listOf("아니", "아니 이름이 이윤아야", "잠깐만", "취소해", "이윤아라고 저장해 줘", "").forEach { assertFalse(it, VoiceApproval.isApproval(it, 1_000)) }
        assertFalse(VoiceApproval.isApproval("응", 60_000))
    }

    @Test
    fun calendarAnswerIsConsentUnlessRefused() {
        listOf("강남세브란스", "없어", "그냥 해", "응").forEach { assertTrue(it, VoiceApproval.isConsent(it, 1_000)) }
        listOf("아니 취소해", "잠깐만", "하지 마", "").forEach { assertFalse(it, VoiceApproval.isConsent(it, 1_000)) }
        assertFalse(VoiceApproval.isConsent("없어", 60_000))
    }
}
