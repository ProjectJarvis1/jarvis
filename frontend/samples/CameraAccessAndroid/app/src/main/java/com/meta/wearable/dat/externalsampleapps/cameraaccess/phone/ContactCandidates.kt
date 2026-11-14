package com.meta.wearable.dat.externalsampleapps.cameraaccess.phone

import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone-book candidates handed to the AI by name and a one-time ID only (D78). Full numbers stay on the device:
 * the AI picks a candidate by ID, the device checks the ID, and only the device dials or opens the SMS app.
 * Same-name candidates are told apart on the device screen by the last four digits, never through the AI.
 */
object ContactCandidates {
    data class Entry(val id: String, val name: String, val number: String, val createdAt: Long, val fromPhoneBook: Boolean = true)

    /** A choice shown on the device card. [lastFour] is for the screen only and is never sent to the AI. */
    data class Choice(val id: String, val name: String, val lastFour: String)

    const val TTL_MS = 30 * 60_000L
    private const val MAX_ENTRIES = 60
    private val ID_PATTERN = Regex("^c_[a-z0-9]{10}$")
    private val random = SecureRandom()
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    @Volatile var clock: () -> Long = System::currentTimeMillis
    private val entries = LinkedHashMap<String, Entry>()

    fun looksLikeId(value: String?): Boolean = value != null && ID_PATTERN.matches(value.trim())

    fun digits(number: String): String = number.filter { it.isDigit() }

    fun lastFour(number: String): String = digits(number).takeLast(4)

    /** Registers a phone-book entry and returns its ID. The same name and number keep one live ID. */
    @Synchronized
    fun register(name: String, number: String, fromPhoneBook: Boolean = true): String {
        prune()
        val key = digits(number)
        entries.values.firstOrNull { it.name == name && digits(it.number) == key && it.fromPhoneBook == fromPhoneBook }
            ?.let { return it.id }
        val id = "c_" + (1..10).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        entries[id] = Entry(id, name, number, clock(), fromPhoneBook)
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        return id
    }

    @Synchronized
    fun resolve(id: String?): Entry? {
        if (!looksLikeId(id)) return null
        prune()
        return entries[id!!.trim()]
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    private fun prune() {
        val now = clock()
        entries.values.removeAll { now - it.createdAt > TTL_MS }
    }

    fun choices(matches: List<Pair<String, String>>): List<Choice> =
        matches.map { (name, number) -> Choice(register(name, number), name, lastFour(number)) }

    /** What search_contacts returns to the AI: names and IDs, no numbers. */
    fun searchResult(query: String, matches: List<Pair<String, String>>): String {
        val names = matches.groupingBy { it.first }.eachCount()
        val list = JSONArray()
        matches.forEachIndexed { index, (name, number) ->
            list.put(JSONObject()
                .put("rank", index + 1)
                .put("candidate_id", register(name, number))
                .put("display_name", name)
                .put("same_name", (names[name] ?: 0) > 1))
        }
        return JSONObject()
            .put("query", query)
            .put("count", matches.size)
            .put("candidates", list)
            .put("note", "전화번호는 기기에만 있습니다. 사용자에게 번호를 말하지 말고 이름으로 확인하세요. " +
                "같은 이름이 여러 명이면 candidate_id 없이 이름으로 호출하세요. 화면에서 사용자가 번호 끝자리를 보고 고릅니다.")
            .toString()
    }

    private val PHONE_KEYS = setOf("phone", "phone_number", "mobile", "tel", "telephone")
    private val NAME_KEYS = listOf("name", "display_name", "person_name")

    /** Korean phone and mobile numbers written in free text (business-card notes, evidence lines). */
    private val PHONE_IN_TEXT = Regex("""(?<![\d])(?:\+?82[-.\s]?0?|0)\d{1,2}[-.\s)]?\d{3,4}[-.\s]?\d{4}(?![\d])""")

    fun redactText(text: String): String = PHONE_IN_TEXT.replace(text, "[전화번호]")

    /**
     * Removes full phone numbers from anything sent to the AI (tool results, task results). A record's phone field
     * becomes a one-time candidate_id, so the AI can still ask the device to call that person (D78).
     */
    fun redact(value: Any?): Any? = when (value) {
        is JSONObject -> {
            val name = NAME_KEYS.firstNotNullOfOrNull { k -> value.optString(k).takeIf { it.isNotBlank() && it != "null" } }
            val out = JSONObject()
            value.keys().forEach { key ->
                val v = value.opt(key)
                if (key.lowercase() in PHONE_KEYS && v is String) {
                    if (digits(v).length >= 7) {
                        out.put("has_phone", true)
                        if (!value.has("candidate_id")) out.put("candidate_id", register(name ?: "이름 없음", v, fromPhoneBook = false))
                    }
                } else {
                    out.put(key, redact(v))
                }
            }
            out
        }
        is JSONArray -> JSONArray().also { arr -> for (i in 0 until value.length()) arr.put(redact(value.opt(i))) }
        is String -> {
            // Tool results often carry JSON as a string; redact inside it the same way.
            val trimmed = value.trimStart()
            val parsed = if (trimmed.startsWith("{")) runCatching { JSONObject(value) }.getOrNull()
                else if (trimmed.startsWith("[")) runCatching { JSONArray(value) }.getOrNull() else null
            parsed?.let { redact(it).toString() } ?: redactText(value)
        }
        else -> value
    }

    /** Rewrites only each function response's payload; call IDs and names are left exactly as the AI sent them. */
    fun redactToolResponse(message: JSONObject): JSONObject {
        val copy = JSONObject(message.toString())
        val list = copy.optJSONObject("toolResponse")?.optJSONArray("functionResponses") ?: return copy
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            item.opt("response")?.let { item.put("response", redact(it)) }
        }
        return copy
    }
}
