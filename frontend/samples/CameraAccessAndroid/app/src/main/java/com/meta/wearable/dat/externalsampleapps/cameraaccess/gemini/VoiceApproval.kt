package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

/**
 * Jarvis asks "이 분을 이윤아로 저장할까요?" out loud before calling a save tool. When the user's
 * last words were a plain yes, that spoken answer is the review: asking again on screen made
 * Jarvis repeat "화면에서 승인해 주세요" after the user had already said "승인".
 * Anything with a no, a correction or extra content is not a yes and still goes to the screen.
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
     * Calendar entries are confirmed in the conversation itself: Jarvis says "장소 있으면 같이, 없으면 그냥 등록할게요",
     * so any recent answer that is not a refusal ("강남세브란스", "없어", "그냥 해") is the go-ahead.
     */
    fun isConsent(utterance: String, ageMs: Long): Boolean {
        if (ageMs !in 0..MAX_AGE_MS) return false
        val t = utterance.trim().lowercase()
        return t.isNotEmpty() && !no.containsMatchIn(t)
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
    /** How soon after the "네" the write it approved must arrive. */
    const val CALL_AFTER_ANSWER_MS = 15_000L

    /** What Jarvis last said (one finished turn) and when it finished. */
    data class Asked(val text: String, val at: Long)

    /**
     * A write the model starts right after a spoken "네" skips its card only when that "네" was the answer to
     * Jarvis's question about exactly this write:
     * - the question is Jarvis's last finished turn, and the answer came after it, within [ANSWER_WINDOW_MS],
     *   with nothing else said in between (so a repeated "네" never counts);
     * - this write was started within [CALL_AFTER_ANSWER_MS] of the answer, and no other task started
     *   between the question and it (that task's question may be what was answered);
     * - the question names this write's key value ([keyArg]: the name, title or note it will save), so a
     *   write whose arguments differ from what was asked is not covered. No key value, no skipping.
     * Calendar entries also take a non-refusal answer ("없어", a place) to "장소도 넣을까요?".
     */
    fun preApproves(asked: Asked?, answer: String, answerAt: Long, previousUtteranceAt: Long, taskCreatedAt: Long,
                    otherTaskCreatedAts: List<Long>, keyArg: String?, calendar: Boolean, now: Long): Boolean {
        if (asked == null || asked.text.isBlank()) return false
        if (answerAt < asked.at || answerAt - asked.at > ANSWER_WINDOW_MS) return false
        if (previousUtteranceAt > asked.at) return false
        if (taskCreatedAt < answerAt || taskCreatedAt - answerAt > CALL_AFTER_ANSWER_MS) return false
        if (otherTaskCreatedAts.any { it in asked.at..taskCreatedAt }) return false
        val key = keyArg?.let(::squash)?.take(12).orEmpty()
        if (key.length < 2 || key !in squash(asked.text)) return false
        val age = now - answerAt
        return isApproval(answer, age) || (calendar && isConsent(answer, age))
    }

    private fun squash(t: String) = t.replace(Regex("""[\s'"‘’“”「」.,!?~]"""), "").lowercase()

    /** True when the words, spaces removed, are only yes-words in a row ("그래저장해줘", "응승인"). */
    private fun splitsIntoYes(t: String): Boolean {
        if (t.isEmpty() || t.length > 12) return false
        val ok = BooleanArray(t.length + 1).also { it[0] = true }
        for (i in 1..t.length) ok[i] = yesWords.any { w -> i >= w.length && ok[i - w.length] && t.regionMatches(i - w.length, w, 0, w.length) }
        return ok[t.length]
    }
}
