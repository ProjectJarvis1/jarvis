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

    /** True when the words, spaces removed, are only yes-words in a row ("그래저장해줘", "응승인"). */
    private fun splitsIntoYes(t: String): Boolean {
        if (t.isEmpty() || t.length > 12) return false
        val ok = BooleanArray(t.length + 1).also { it[0] = true }
        for (i in 1..t.length) ok[i] = yesWords.any { w -> i >= w.length && ok[i - w.length] && t.regionMatches(i - w.length, w, 0, w.length) }
        return ok[t.length]
    }
}
