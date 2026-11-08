package com.meta.wearable.dat.externalsampleapps.cameraaccess.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BusinessCardTextTest {
    private val card = """
        주식회사 한빛테크
        홍길동
        영업팀 과장
        M. 010-1234-5678
        hong@hanbit.co.kr
    """.trimIndent()

    @Test
    fun readsATypicalKoreanCard() {
        val parsed = BusinessCardText.parse(card)
        assertNotNull(parsed); parsed!!
        assertEquals(setOf("01012345678"), parsed.phones)
        assertEquals(setOf("hong@hanbit.co.kr"), parsed.emails)
        assertEquals("홍길동", parsed.name)
        assertEquals("주식회사 한빛테크", parsed.org)
    }

    @Test
    fun foldsInternationalPrefix() {
        assertEquals("01012345678", BusinessCardText.normalizePhone("+82 10-1234-5678"))
        assertNull(BusinessCardText.normalizePhone("2026-11-08"))
    }

    @Test
    fun rejectsTextWithoutCardCues() {
        assertNull(BusinessCardText.parse("오늘 회의\n안건 정리\n다음 주 월요일"))           // no contact
        assertNull(BusinessCardText.parse("배달 문의\n지금 주문하세요\n010-9999-0000"))       // contact but no card cue
        assertNull(BusinessCardText.parse("홍길동\n010-1234-5678"))                           // too few lines
        assertNull(BusinessCardText.parse("치킨나라\n주문 전화\n010-1234-5678"))              // shop name is not a card cue
        assertNull(BusinessCardText.parse(("긴 본문 내용입니다 ".repeat(40)) + "\n홍길동\n010-1234-5678")) // a page of text
    }

    @Test
    fun offersOnlyAfterTwoMatchingLooksAndNotAgainSoon() {
        val seen = CardSightings()
        val c = BusinessCardText.parse(card)!!
        assertNull(seen.onFrame(c, 1_000))            // first look
        assertEquals(c, seen.onFrame(c, 2_000))       // second look: offer
        seen.markOffered(c, 2_000)
        assertNull(seen.onFrame(c, 3_000))
        assertNull(seen.onFrame(c, 4_000))            // already offered
        val misread = BusinessCardText.parse(card.replace("홍길동", "홍길둥"))!!
        assertNull(seen.onFrame(misread, 5_000))      // same phone, OCR noise: still the same card
        assertNull(seen.onFrame(c, 2_000 + 10 * 60_000L + 1))
        assertEquals(c, seen.onFrame(c, 2_000 + 10 * 60_000L + 500)) // ten minutes later: may ask again
    }

    @Test
    fun aGapOrDifferentCardResetsThePair() {
        val seen = CardSightings()
        val c = BusinessCardText.parse(card)!!
        assertNull(seen.onFrame(c, 1_000))
        assertNull(seen.onFrame(null, 2_000))
        assertNull(seen.onFrame(c, 3_000))
        assertNull(seen.onFrame(c, 7_000))            // more than 3s apart
    }
}
