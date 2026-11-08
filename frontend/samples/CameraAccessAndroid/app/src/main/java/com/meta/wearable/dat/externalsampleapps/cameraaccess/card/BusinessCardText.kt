package com.meta.wearable.dat.externalsampleapps.cameraaccess.card

/**
 * Decides from recognized text alone whether the camera is looking at a business card.
 *
 * A contact (phone or email) is required, plus one more card cue: a second contact, or a company or
 * job title word. Long text (a web page, a document, a flyer full of
 * copy) is rejected so a phone number on a poster does not trigger a save offer.
 */
object BusinessCardText {
    data class Card(val phones: Set<String>, val emails: Set<String>, val name: String?, val org: String?, val text: String) {
        /** Mobile numbers and emails belong to one person; office and fax numbers are shared by a company. */
        val personal: Set<String> get() = phones.filter { MOBILE.matches(it) }.toSet() + emails

        /**
         * Same card when a personal contact matches, so OCR noise elsewhere does not matter. Coworkers share
         * the office number, so that only counts when one of the cards shows nothing personal.
         */
        fun sameAs(other: Card): Boolean {
            if (personal.isNotEmpty() && other.personal.isNotEmpty()) return personal.any { it in other.personal }
            return phones.any { it in other.phones } || emails.any { it in other.emails }
        }
    }

    private const val MAX_TEXT = 420
    private val MOBILE = Regex("""01[016789]\d{7,8}""")
    private val webRegex = Regex("""\S+@\S+|(?:https?://|www\.)\S+|\S+\.(?:com|co\.kr|kr|net|org|io)\S*""", RegexOption.IGNORE_CASE)
    private val phoneRegex = Regex("""(?:\+82[\s.-]?)?\(?0?\d{1,3}\)?[\s.-]?\d{3,4}[\s.-]?\d{4}""")
    private val emailRegex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val orgRegex = Regex("""주식회사|\(주\)|㈜|유한회사|그룹|은행|병원|대학교|연구소|재단|협회|법인|\bInc\b\.?|\bCorp\b\.?|\bCo\.,?(?=\s|$)|\bLtd\b\.?|\bLLC\b|\bCompany\b""", RegexOption.IGNORE_CASE)
    private val titleRegex = Regex("""대표|이사|부장|차장|과장|대리|팀장|실장|본부장|매니저|선임|책임|수석|연구원|변호사|변리사|교수|원장|CEO|CTO|Manager|Director""", RegexOption.IGNORE_CASE)
    private val labelWords = setOf("대표", "이사", "부장", "차장", "과장", "대리", "팀장", "실장", "주소", "전화", "휴대폰", "팩스", "메일", "이메일", "회사", "본사", "지사")
    private val nameRegex = Regex("""^[가-힣]{2,4}$|^[가-힣](?: [가-힣]){1,3}$""")

    fun parse(raw: String): Card? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_TEXT) return null
        val phones = phoneRegex.findAll(text).mapNotNull { normalizePhone(it.value) }.toSet()
        val emails = emailRegex.findAll(text).map { it.value.lowercase() }.toSet()
        if (phones.isEmpty() && emails.isEmpty()) return null
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 3) return null
        val name = lines.firstOrNull { line -> nameRegex.matches(line) && line.replace(" ", "") !in labelWords }?.replace(" ", "")
        // Company and title words are looked for outside emails and web addresses (".co.kr" is not "Co.").
        val plain = lines.map { webRegex.replace(it, " ").trim() }
        val org = lines.indices.firstOrNull { orgRegex.containsMatchIn(plain[it]) }?.let { lines[it] }
        // A name-like line alone is too weak (a shop name on a flyer looks the same); it only labels the offer.
        val strongCue = phones.size + emails.size >= 2 || org != null || plain.any { titleRegex.containsMatchIn(it) }
        if (!strongCue) return null
        return Card(phones, emails, name, org, text)
    }

    /** Digits only, +82 folded to a leading 0; anything too short to be a Korean number is dropped. */
    internal fun normalizePhone(value: String): String? {
        var digits = value.filter(Char::isDigit)
        if (value.trim().startsWith("+82")) digits = "0" + digits.removePrefix("82")
        return digits.takeIf { it.length in 9..11 && it.startsWith("0") }
    }
}

/**
 * Frame-by-frame gate in front of the save offer: a card must be read the same in two looks in a row,
 * and a card already offered is not offered again for [repeatMs], even when OCR reads it a bit differently.
 */
class CardSightings(private val repeatMs: Long = 10 * 60_000L, private val pairWindowMs: Long = 3_000L) {
    private var previous: Pair<BusinessCardText.Card, Long>? = null
    private val offered = ArrayDeque<Pair<BusinessCardText.Card, Long>>()

    /** Returns the card to offer now, or null. [card] is null when the frame showed no card. */
    @Synchronized
    fun onFrame(card: BusinessCardText.Card?, nowMs: Long): BusinessCardText.Card? {
        while (offered.isNotEmpty() && nowMs - offered.first().second > repeatMs) offered.removeFirst()
        if (card == null) { previous = null; return null }
        val before = previous
        previous = card to nowMs
        if (before == null || nowMs - before.second > pairWindowMs || !card.sameAs(before.first)) return null
        if (offered.any { it.first.sameAs(card) }) return null
        return card
    }

    /** Call once the offer was actually made, so the same card stays quiet for a while. */
    @Synchronized
    fun markOffered(card: BusinessCardText.Card, nowMs: Long) {
        offered.addLast(card to nowMs)
        previous = null
    }
}
