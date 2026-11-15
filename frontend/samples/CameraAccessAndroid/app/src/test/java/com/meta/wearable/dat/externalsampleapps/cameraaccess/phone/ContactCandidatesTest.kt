package com.meta.wearable.dat.externalsampleapps.cameraaccess.phone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContactCandidatesTest {
    private var now = 1_000_000L

    @Before fun setUp() { ContactCandidates.clear(); ContactCandidates.clock = { now } }
    @After fun tearDown() { ContactCandidates.clear(); ContactCandidates.clock = System::currentTimeMillis }

    private val numbers = listOf("010-1234-5678", "01098765432", "+82 10-5555-4321")

    private fun assertNoNumber(text: String) {
        numbers.forEach { n ->
            assertFalse("full number leaked: $n in $text", text.contains(n))
            assertFalse("digits leaked: $n in $text", text.contains(ContactCandidates.digits(n).takeLast(8)))
        }
    }

    @Test fun searchResultCarriesNamesAndIdsOnly() {
        val out = ContactCandidates.searchResult("김민수", listOf("김민수" to numbers[0], "김민수" to numbers[1], "김민정" to numbers[2]))
        assertNoNumber(out)
        assertFalse(out.contains("5678"))
        val list = JSONObject(out).getJSONArray("candidates")
        assertEquals(3, list.length())
        assertTrue(list.getJSONObject(0).getBoolean("same_name"))
        assertFalse(list.getJSONObject(2).getBoolean("same_name"))
        val id = list.getJSONObject(0).getString("candidate_id")
        assertTrue(ContactCandidates.looksLikeId(id))
        assertEquals(numbers[0], ContactCandidates.resolve(id)!!.number)
    }

    @Test fun idsExpireAndClearOnSignOut() {
        val id = ContactCandidates.register("김민수", numbers[0])
        assertNotNull(ContactCandidates.resolve(id))
        now += ContactCandidates.TTL_MS + 1
        assertNull(ContactCandidates.resolve(id))
        val again = ContactCandidates.register("김민수", numbers[0])
        ContactCandidates.clear()
        assertNull(ContactCandidates.resolve(again))
    }

    @Test fun madeUpOrForeignIdsDoNotResolve() {
        assertNull(ContactCandidates.resolve("c_aaaaaaaaaa"))
        assertNull(ContactCandidates.resolve("01012345678"))
        assertNull(ContactCandidates.resolve(null))
    }

    @Test fun choicesKeepLastFourOnTheDevice() {
        val choices = ContactCandidates.choices(listOf("김민수" to numbers[0], "김민수" to numbers[1]))
        assertEquals(listOf("5678", "5432"), choices.map { it.lastFour })
        assertTrue(choices.all { ContactCandidates.resolve(it.id) != null })
    }

    @Test fun recordPhonesBecomeCandidateIdsInToolResults() {
        val person = JSONObject().put("id", "p1").put("name", "박과장").put("org", "세진메디칼").put("phone", numbers[0])
        val payload = JSONObject().put("person_candidates", JSONArray().put(JSONObject().put("person", person).put("score", 0.9)
            .put("evidence", "명함: 박과장 010-1234-5678, 세진메디칼")))
        val out = ContactCandidates.redact(payload) as JSONObject
        val text = out.toString()
        assertNoNumber(text)
        val redacted = out.getJSONArray("person_candidates").getJSONObject(0).getJSONObject("person")
        assertTrue(redacted.getBoolean("has_phone"))
        val entry = ContactCandidates.resolve(redacted.getString("candidate_id"))!!
        assertEquals(numbers[0], entry.number)
        assertFalse(entry.fromPhoneBook)
        assertTrue(text.contains("[전화번호]"))
    }

    @Test fun jsonInsideStringsAndFreeTextAreRedacted() {
        val inner = JSONObject().put("display_name", "김민수").put("phone_number", numbers[1]).toString()
        val response = JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(
            JSONObject().put("id", "call-01012345678").put("name", "search_contacts")
                .put("response", JSONObject().put("result", inner).put("note", "연락처 +82 10-5555-4321 확인")))))
        val out = ContactCandidates.redactToolResponse(response)
        val item = out.getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        // The call ID is the AI's own and is never rewritten, even when it looks like a number.
        assertEquals("call-01012345678", item.getString("id"))
        assertNoNumber(item.getJSONObject("response").toString())
    }

    @Test fun ordinaryNumbersAreKept() {
        val text = "견적 2026-11-16, 수량 1200개, 금액 35000000원, 회의 10:30"
        assertEquals(text, ContactCandidates.redactText(text))
    }

    @Test fun parenthesizedAndSpacedNumbersAreRedacted() {
        listOf("연락처 02) 1234-5678", "연락처 (010) 1234-5678", "대표 (02)1234-5678", "+82 (10) 1234 5678",
            "010.1234.5678", "010 - 1234 - 5678").forEach { text ->
            val out = ContactCandidates.redactText(text)
            assertFalse("not redacted: $text -> $out", out.contains("5678"))
            assertTrue(out.contains("[전화번호]"))
        }
    }

    @Test fun sessionContextStringIsRedacted() {
        val context = JSONObject().put("references", JSONArray().put(JSONObject()
            .put("excerpt", "박과장 메모: 휴대폰 010-1234-5678로 연락").put("task_id", "t1"))).toString()
        val out = ContactCandidates.redact(context).toString()
        assertNoNumber(out)
        assertTrue(out.contains("t1"))
    }

    @Test fun emailAddressesWithNumbersStayUsable() {
        assertEquals("보낸 사람 01012345678@naver.com", ContactCandidates.redactText("보낸 사람 01012345678@naver.com"))
        val out = ContactCandidates.redact(JSONObject().put("from", "김민수 <01012345678@naver.com>").put("note", "휴대폰 010-1234-5678")) as JSONObject
        assertEquals("김민수 <01012345678@naver.com>", out.getString("from"))
        assertTrue(out.getString("note").contains("[전화번호]"))
    }

    @Test fun knownAndInternationalNumbersAreRemovedFromNotesToo() {
        val person = JSONObject().put("name", "Kim").put("phone", "+1-415-555-0123").put("notes_summary", "연락처 +1-415-555-0123, 사무실 1 (415) 555 0123")
        val out = (ContactCandidates.redact(JSONObject().put("person", person)) as JSONObject).toString()
        assertFalse(out, out.contains("555"))
        assertTrue(out.contains("candidate_id"))
        assertEquals("런던 [전화번호] 대표", ContactCandidates.redactText("런던 +44 20 7946 0958 대표"))
        assertEquals("2026-11-16 10:30 회의, 수량 1200개", ContactCandidates.redactText("2026-11-16 10:30 회의, 수량 1200개"))
    }
}
