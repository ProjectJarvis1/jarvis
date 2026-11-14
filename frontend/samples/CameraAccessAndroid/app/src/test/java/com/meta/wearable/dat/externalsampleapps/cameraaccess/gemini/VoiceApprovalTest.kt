package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
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
    fun recordChangeTakesVoiceOnlyOnceItsPreviewIsShown() {
        val v = VoiceApproval
        // Not loaded yet, failed, or never shown: only a tap.
        assertNull(v.presentedAtForVoice("apply_record_change", 2, 1_000L, null, null))
        assertNull(v.presentedAtForVoice("undo_record_change", 2, 1_000L, null, null))
        // Shown for an older revision (edited since): not this preview.
        assertNull(v.presentedAtForVoice("apply_record_change", 2, 1_000L, 1, 1_500L))
        // Shown: the answer window starts when the preview appeared, not when the task was made.
        assertEquals(5_000L, v.presentedAtForVoice("apply_record_change", 2, 1_000L, 2, 5_000L))
        assertFalse(v.answersWaitingWrite(5_000L, 4_000L, 0L, emptyList()))  // a yes said before the preview
        assertTrue(v.answersWaitingWrite(5_000L, 6_000L, 0L, emptyList()))
        // Other saves are unchanged.
        assertEquals(1_000L, v.presentedAtForVoice("save_person", 2, 1_000L, null, null))
    }
}
