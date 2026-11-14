package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

/**
 * Saves and outside writes wait on a confirmation card. A plain spoken yes to that card's own question
 * approves it like a tap ([answersWaitingWrite]); a "네" said before the write existed never does, since it
 * may have answered another question. Anything with a no, a correction or extra content is not a yes.
 */
object VoiceApproval {
    private const val MAX_AGE_MS = 30_000L
    private val yesWords = listOf("응", "어", "네", "예", "넵", "넹", "그래", "그래요", "맞아", "맞아요", "좋아", "좋아요",
        "승인", "승인해", "승인할게", "확인", "확인해", "확인했어", "했다고", "했어", "저장해", "저장해줘", "저장해주세요", "그걸로", "그렇게해", "그렇게해줘", "오케이", "ok", "okay", "ㅇㅇ")
    private val no = Regex("아니|말고|취소|잠깐|잠시만|틀렸|다시|안 ?돼|하지 ?마")

    fun isApproval(utterance: String, ageMs: Long): Boolean {
        if (ageMs !in 0..MAX_AGE_MS) return false
        val t = utterance.trim().trimEnd('.', '!', '?', ' ').lowercase()
        if (t.isEmpty() || no.containsMatchIn(t)) return false
        return splitsIntoYes(t.replace(Regex("[ ,]+"), ""))
    }

    /**
     * A spoken "네" approves a waiting write only when it can only be the answer to that write's own
     * confirmation, put to the user when this revision went on screen ([presentedAt]):
     * - said after that moment and within [ANSWER_WINDOW_MS] (a "네" from before an edit, or a late one, is not it);
     * - nothing else said in between ([previousUtteranceAt]), allowing [LATE_TRANSCRIPT_MS] for the request's
     *   own transcript to land after the tool call; a second "네" therefore never counts;
     * - no other task started since, as its question ("이 분 김 팀장님 맞나요?") may be what the "네" answers.
     * Otherwise the card waits for a tap. The caller approves exactly that task id and revision.
     */
    fun answersWaitingWrite(presentedAt: Long, utteranceAt: Long, previousUtteranceAt: Long, newerTaskCreatedAts: List<Long>): Boolean {
        if (utteranceAt < presentedAt || utteranceAt - presentedAt > ANSWER_WINDOW_MS) return false
        if (previousUtteranceAt > presentedAt + LATE_TRANSCRIPT_MS) return false
        return newerTaskCreatedAts.none { it > presentedAt }
    }

    const val LATE_TRANSCRIPT_MS = 3_000L
    const val ANSWER_WINDOW_MS = 20_000L

    /** True when the words, spaces removed, are only yes-words in a row ("그래저장해줘", "응승인"). */
    private fun splitsIntoYes(t: String): Boolean {
        if (t.isEmpty() || t.length > 12) return false
        val ok = BooleanArray(t.length + 1).also { it[0] = true }
        for (i in 1..t.length) ok[i] = yesWords.any { w -> i >= w.length && ok[i - w.length] && t.regionMatches(i - w.length, w, 0, w.length) }
        return ok[t.length]
    }
}
