package com.meta.wearable.dat.externalsampleapps.cameraaccess.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MailDraftTest {
    @Test fun takesTheAddressOutOfAHeader() {
        val d = MailDraft.from("김 팀장 <kim@daesung.example>", "기술협상 참여 요청", "참석하겠습니다.").getOrThrow()
        assertEquals("kim@daesung.example", d.to)
        assertEquals("Re: 기술협상 참여 요청", d.subject)
    }

    @Test fun refusesMoreThanOneRecipient() {
        assertTrue(MailDraft.from("a@x.example, b@y.example", "s", "b").isFailure)
    }

    @Test fun refusesNoAddressOrEmptyBody() {
        assertTrue(MailDraft.from("김 팀장", "s", "b").isFailure)
        assertTrue(MailDraft.from("a@x.example", "s", "   ").isFailure)
    }

    @Test fun addsReOnceWhateverTheOriginalHad() {
        assertEquals("Re: 견적", MailDraft.replySubject("RE: Re: 견적"))
        assertEquals("Re: 견적", MailDraft.replySubject("회신: 견적"))
        assertEquals("Re:", MailDraft.replySubject(null))
    }

    @Test fun mailtoEncodesSpacesAsPercent20AndKeepsTheBody() {
        val uri = MailDraft.mailto(MailDraft("kim@daesung.example", "Re: 견적 문의", "내일 보내 드릴게요.\n감사합니다."))
        assertTrue(uri.startsWith("mailto:kim@daesung.example?subject="))
        assertTrue("%20" in uri && "+" !in uri.substringAfter("?"))
        assertTrue("%0A" in uri)
    }

    @Test fun clipsAVeryLongBody() {
        val d = MailDraft.from("a@x.example", "s", "가".repeat(MailDraft.MAX_BODY + 50)).getOrThrow()
        assertEquals(MailDraft.MAX_BODY, d.body.length)
    }

    @Test fun keepsTheWholeAddressNeverAFragment() {
        assertEquals("support/ticket@example.com", MailDraft.address("support/ticket@example.com").getOrThrow())
        assertEquals("sales=uk@example.com", MailDraft.address("영업 <sales=uk@example.com>").getOrThrow())
        assertTrue(MailDraft.address("문의는 kim@x.example 로").isFailure)  // a sentence is not an address
        assertTrue(MailDraft.address("<a@x.example> <b@y.example>").isFailure)
    }
}
