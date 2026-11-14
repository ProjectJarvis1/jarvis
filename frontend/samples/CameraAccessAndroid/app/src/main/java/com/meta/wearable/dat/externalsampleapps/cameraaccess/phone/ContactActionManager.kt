package com.meta.wearable.dat.externalsampleapps.cameraaccess.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Intents.Insert
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.externalsampleapps.cameraaccess.AppContextProvider
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import java.util.Locale

class ContactActionManager {
    private val context
        get() = AppContextProvider.require()

    fun callContact(query: String, candidateId: String? = null): ToolResult {
        if (!hasContactsPermission()) {
            return ToolResult.Failure("전화번호부 권한이 없습니다. 연락처 권한을 허용한 뒤 다시 시도해주세요.")
        }
        if (!hasCallPermission()) {
            return ToolResult.Failure("전화 발신 권한이 없습니다. 전화 권한을 허용한 뒤 다시 시도해주세요.")
        }
        val match = when (val target = target(query, candidateId)) {
            is Target.One -> target.match
            else -> return target.failure(query)
        }
        launchCallIntent(match.phoneNumber)
        return ToolResult.Success("${match.displayName}에게 전화를 걸었습니다.")
    }

    fun textContact(query: String, message: String, candidateId: String? = null): ToolResult {
        if (message.isBlank()) {
            return ToolResult.Failure("보낼 문자 내용을 함께 알려주세요.")
        }
        if (!hasContactsPermission()) {
            return ToolResult.Failure("전화번호부 권한이 없습니다. 연락처 권한을 허용한 뒤 다시 시도해주세요.")
        }
        val match = when (val target = target(query, candidateId)) {
            is Target.One -> target.match
            else -> return target.failure(query)
        }
        openSmsComposer(match.phoneNumber, message)
        return ToolResult.Success("${match.displayName}에게 보낼 문자를 문자 앱에 띄웠습니다. 휴대폰에서 전송 버튼을 누르면 보내집니다.")
    }

    /**
     * The people the device card offers for [query]: one when the AI named a live candidate ID or the name is unique,
     * several when names repeat. The last four digits go to the card only (D78).
     */
    fun choicesFor(query: String, candidateId: String? = null): List<ContactCandidates.Choice> {
        if (!hasContactsPermission()) return emptyList()
        return when (val target = target(query, candidateId)) {
            is Target.One -> ContactCandidates.choices(listOf(target.match.displayName to target.match.phoneNumber))
            is Target.Many -> ContactCandidates.choices(target.matches.map { it.displayName to it.phoneNumber })
            else -> emptyList()
        }
    }

    private sealed interface Target {
        data class One(val match: ContactMatch) : Target
        data class Many(val matches: List<ContactMatch>) : Target
        object None : Target
        object Stale : Target

        // Failures read back to the AI carry names and counts only, never numbers.
        fun failure(query: String): ToolResult = ToolResult.Failure(when (this) {
            is Many -> "'$query' 연락처가 ${matches.size}명이에요. 화면에서 번호 끝자리를 보고 한 명을 골라 주세요."
            Stale -> "고른 연락처를 전화번호부에서 다시 찾지 못했어요. 이름으로 다시 찾아 주세요."
            else -> "전화번호부에서 '$query'에 해당하는 연락처를 찾지 못했습니다."
        })
    }

    /** Resolves the person to act on. A candidate ID is checked against the phone book again before use. */
    private fun target(query: String, candidateId: String?): Target {
        val id = candidateId?.trim()?.takeIf { ContactCandidates.looksLikeId(it) }
            ?: query.trim().takeIf { ContactCandidates.looksLikeId(it) }
        if (id != null) {
            val entry = ContactCandidates.resolve(id) ?: return Target.Stale
            // A number from a Jarvis record (a saved business card) is used as kept; a phone-book entry is
            // looked up again so a contact deleted or changed since the search is not dialled.
            if (!entry.fromPhoneBook) return Target.One(ContactMatch(entry.name, entry.number))
            val live = findMatches(entry.name).firstOrNull { normalizeNumber(it.phoneNumber) == normalizeNumber(entry.number) }
            return live?.let { Target.One(it) } ?: Target.Stale
        }
        val matches = findMatches(query)
        return when {
            matches.isEmpty() -> Target.None
            matches.size == 1 -> Target.One(matches.first())
            else -> Target.Many(matches)
        }
    }

    fun createContact(
        name: String,
        phone: String?,
        email: String?,
        org: String?,
        role: String?,
        notes: String?,
    ): ToolResult {
        if (name.isBlank()) {
            return ToolResult.Failure("저장할 연락처 이름을 알려주세요.")
        }
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(Insert.NAME, name)
            phone?.takeIf { it.isNotBlank() }?.let { putExtra(Insert.PHONE, it) }
            email?.takeIf { it.isNotBlank() }?.let { putExtra(Insert.EMAIL, it) }
            org?.takeIf { it.isNotBlank() }?.let { putExtra(Insert.COMPANY, it) }
            role?.takeIf { it.isNotBlank() }?.let { putExtra(Insert.JOB_TITLE, it) }
            notes?.takeIf { it.isNotBlank() }?.let { putExtra(Insert.NOTES, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult.Success("$name 연락처 등록 화면을 열었습니다. 내용을 확인한 뒤 저장해주세요.")
    }

    fun searchContacts(query: String, limit: Int = 5): ToolResult {
        if (!hasContactsPermission()) {
            return ToolResult.Failure("전화번호부 권한이 없습니다. 연락처 권한을 허용한 뒤 다시 시도해주세요.")
        }
        if (query.isBlank()) {
            return ToolResult.Failure("검색할 연락처 이름을 알려주세요.")
        }
        val matches = findMatches(query).take(limit.coerceIn(1, 10))
        // Names and one-time IDs only: the full numbers never leave the device (D78).
        return ToolResult.Success(ContactCandidates.searchResult(query, matches.map { it.displayName to it.phoneNumber }))
    }

    fun speechContextHint(limit: Int = 80): String {
        if (!hasContactsPermission()) return ""
        val names = linkedSetOf<String>()
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} IS NOT NULL",
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val displayNameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            while (cursor.moveToNext() && names.size < limit) {
                val displayName = cursor.getString(displayNameIndex).orEmpty().trim()
                if (displayName.isNotBlank()) {
                    names.add(displayName)
                }
            }
        }
        if (names.isEmpty()) return ""
        return "연락처 이름 후보: ${names.joinToString(", ")}"
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasCallPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE,
        ) == PackageManager.PERMISSION_GRANTED

    private fun findMatches(query: String): List<ContactMatch> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()

        val matches = linkedMapOf<String, ContactMatch>()
        val numberQuery = normalizeNumber(query)
        val selection = """
            ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} IS NOT NULL
            AND (
                ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?
                OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?
            )
        """.trimIndent()
        val args = arrayOf("%$query%", "%$query%")
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val lookupKeyIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
            val displayNameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(displayNameIndex).orEmpty().trim()
                val phoneNumber = cursor.getString(numberIndex).orEmpty().trim()
                if (displayName.isBlank() || phoneNumber.isBlank()) continue
                val normalizedName = normalize(displayName)
                val normalizedPhone = normalizeNumber(phoneNumber)
                if (
                    normalizedName.contains(normalizedQuery) ||
                    (numberQuery.isNotBlank() && normalizedPhone.contains(numberQuery))
                ) {
                    val lookupKey = cursor.getString(lookupKeyIndex).orEmpty()
                    matches.putIfAbsent(
                        "$lookupKey::$normalizedPhone",
                        ContactMatch(displayName, phoneNumber),
                    )
                }
            }
        }

        if (matches.isEmpty()) {
            val allContacts = collectAllContacts()
            allContacts
                .filter { contact ->
                    normalize(contact.displayName).contains(normalizedQuery) ||
                        normalizedQuery.contains(normalize(contact.displayName))
                }
                .forEach { contact ->
                    matches.putIfAbsent(
                        "${contact.displayName}::${normalizeNumber(contact.phoneNumber)}",
                        contact,
                    )
                }
            // Fall back to format-insensitive number matching (hyphens/spaces),
            // so a number picked from search_contacts still resolves to a real contact.
            if (matches.isEmpty() && numberQuery.length >= 4) {
                allContacts
                    .filter { contact ->
                        val stored = normalizeNumber(contact.phoneNumber)
                        stored.contains(numberQuery) || numberQuery.contains(stored)
                    }
                    .forEach { contact ->
                        matches.putIfAbsent(
                            "${contact.displayName}::${normalizeNumber(contact.phoneNumber)}",
                            contact,
                        )
                    }
            }
        }

        val exactNameMatches = matches.values.filter { normalize(it.displayName) == normalizedQuery }
        return if (exactNameMatches.isNotEmpty()) exactNameMatches else matches.values.toList()
    }

    private fun collectAllContacts(): List<ContactMatch> {
        val contacts = linkedMapOf<String, ContactMatch>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} IS NOT NULL",
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val lookupKeyIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
            val displayNameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(displayNameIndex).orEmpty().trim()
                val phoneNumber = cursor.getString(numberIndex).orEmpty().trim()
                if (displayName.isBlank() || phoneNumber.isBlank()) continue
                contacts.putIfAbsent(
                    "${cursor.getString(lookupKeyIndex).orEmpty()}::${normalizeNumber(phoneNumber)}",
                    ContactMatch(displayName, phoneNumber),
                )
            }
        }
        return contacts.values.toList()
    }

    private fun launchCallIntent(phoneNumber: String) {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:${Uri.encode(phoneNumber)}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Google Play allows SEND_SMS only for default SMS apps, so the text opens in the user's SMS app to send. */
    private fun openSmsComposer(phoneNumber: String, message: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${Uri.encode(phoneNumber)}")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.getDefault()).replace("\\s+".toRegex(), "")

    private fun normalizeNumber(value: String): String =
        value.filter { it.isDigit() || it == '+' }

    private data class ContactMatch(
        val displayName: String,
        val phoneNumber: String,
    )
}
