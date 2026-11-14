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

    @Test
    fun cardYesOnlyAnswersThatCardsOwnQuestion() {
        val shown = 100_000L // when this revision went on screen
        // Asked, then answered within the window: approves.
        assertTrue(VoiceApproval.answersWaitingWrite(shown, utteranceAt = shown + 4_000, previousUtteranceAt = 98_000, newerTaskCreatedAts = emptyList()))
        // Late "네" (after the 20 s window).
        assertFalse(VoiceApproval.answersWaitingWrite(shown, shown + 25_000, 98_000, emptyList()))
        // "네" to another question: another task started after this one went on screen.
        assertFalse(VoiceApproval.answersWaitingWrite(shown, shown + 4_000, 98_000, listOf(shown + 2_000)))
        // Repeated "네": something was already said after the question.
        assertFalse(VoiceApproval.answersWaitingWrite(shown, shown + 9_000, previousUtteranceAt = shown + 4_000, newerTaskCreatedAts = emptyList()))
        // Arguments edited after the "네": the new revision went on screen after it was said.
        assertFalse(VoiceApproval.answersWaitingWrite(presentedAt = shown + 6_000, utteranceAt = shown + 4_000, previousUtteranceAt = 98_000, newerTaskCreatedAts = emptyList()))
    }

    @Test
    fun yesBeforeAWriteOnlyCountsForTheQuestionAboutThatWrite() {
        val asked = VoiceApproval.Asked("이 분을 이유나 님으로 저장할까요?", 200_000L)
        fun pre(answer: String = "응", answerAt: Long = 203_000, previous: Long = 190_000, created: Long = 205_000,
                others: List<Long> = listOf(185_000), key: String? = "이유나", now: Long = 205_100, q: VoiceApproval.Asked? = asked) =
            VoiceApproval.preApproves(q, answer, answerAt, previous, created, others, key, calendar = false, now = now)
        assertTrue(pre())
        // "네" to a different question: the last question was about someone else, or another task started since.
        assertFalse(pre(q = VoiceApproval.Asked("김 팀장님 맞나요?", 200_000L)))
        assertFalse(pre(others = listOf(201_000)))
        // Late "네", or a write started long after it.
        assertFalse(pre(answerAt = 225_000, created = 226_000, now = 226_100))
        assertFalse(pre(created = 230_000, now = 230_100))
        // Repeated "네": the user had already answered after the question.
        assertFalse(pre(previous = 201_500))
        // Arguments changed from what was asked ("이유나" asked, "이윤아" saved), or no key value at all.
        assertFalse(pre(key = "이윤아"))
        assertFalse(pre(key = null))
        // Not a yes.
        assertFalse(pre(answer = "아니 잠깐"))
    }
}
