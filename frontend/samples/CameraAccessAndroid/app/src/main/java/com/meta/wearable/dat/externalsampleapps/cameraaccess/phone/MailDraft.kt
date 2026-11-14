package com.meta.wearable.dat.externalsampleapps.cameraaccess.phone

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.meta.wearable.dat.externalsampleapps.cameraaccess.AppContextProvider
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import java.net.URLEncoder

/**
 * A reply draft (D55 step 2). Jarvis writes it from a mail it read; the app only opens the mail app's compose
 * screen with it filled in, and the user sends it there. No Gmail write permission, nothing kept on the server.
 */
data class MailDraft(val to: String, val subject: String, val body: String) {
    companion object {
        const val MAX_BODY = 8_000
        /** A whole address (RFC 5322 dot-atom local part). Matched against the full candidate, never searched inside it. */
        private val ADDRESS = Regex("""[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
        private val BRACKETED = Regex("""<([^<>]*)>""")

        /**
         * The one address in [header]: either a bare address, or "이름 <address>" with exactly one bracket. Anything
         * else (a list, a malformed header) is refused rather than guessed at, so a reply never goes to a fragment.
         */
        fun address(header: String?): Result<String> {
            val raw = header.orEmpty().trim()
            if (raw.count { it == '@' } > 1) return Result.failure(IllegalArgumentException("받는 사람은 한 명만 넣어 주세요."))
            val inside = BRACKETED.findAll(raw).map { it.groupValues[1].trim() }.toList()
            val candidate = when (inside.size) {
                0 -> raw
                1 -> inside.single()
                else -> return Result.failure(IllegalArgumentException("받는 사람은 한 명만 넣어 주세요."))
            }
            return if (ADDRESS.matches(candidate)) Result.success(candidate)
            else Result.failure(IllegalArgumentException("받는 사람 메일 주소가 필요합니다. 읽은 메일의 답장 주소(없으면 보낸 사람 주소)를 넣어 주세요."))
        }

        /**
         * Builds a draft from the tool's arguments, or returns why it cannot. [to] may be a header such as
         * "김 팀장 <kim@example.com>"; exactly one address must come out of it, so a draft never goes to a
         * list of people the user did not see.
         */
        fun from(to: String?, subject: String?, body: String?): Result<MailDraft> {
            val address = address(to).getOrElse { return Result.failure(it) }
            val text = body.orEmpty().trim()
            if (text.isBlank()) return Result.failure(IllegalArgumentException("답장 내용이 비어 있습니다."))
            return Result.success(MailDraft(address, replySubject(subject), text.take(MAX_BODY)))
        }

        /** "Re:" once, whatever the original had ("RE:", "Re: Re:", or a Korean "회신:"). */
        fun replySubject(subject: String?): String {
            val base = subject.orEmpty().trim().replace(Regex("""^((re|fw|fwd|회신|답장)\s*:\s*)+""", RegexOption.IGNORE_CASE), "").trim()
            return if (base.isEmpty()) "Re:" else "Re: $base"
        }

        /** RFC 6068 mailto: spaces as %20 (not +), so the mail app shows the text as written. */
        fun mailto(d: MailDraft): String {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
            return "mailto:${enc(d.to).replace("%40", "@")}?subject=${enc(d.subject)}&body=${enc(d.body)}"
        }
    }
}

object MailComposer {
    private const val GMAIL = "com.google.android.gm"

    /** Opens the compose screen (Gmail first, any mail app otherwise). Nothing is sent from here. */
    fun open(d: MailDraft): ToolResult {
        val context = AppContextProvider.require()
        fun intent(pkg: String?) = Intent(Intent.ACTION_SENDTO, Uri.parse(MailDraft.mailto(d))).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(d.to))
            putExtra(Intent.EXTRA_SUBJECT, d.subject)
            putExtra(Intent.EXTRA_TEXT, d.body)
            pkg?.let(::setPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            try { context.startActivity(intent(GMAIL)) } catch (_: ActivityNotFoundException) { context.startActivity(intent(null)) }
            ToolResult.Success("메일 앱에 ${d.to} 앞 답장 초안을 띄웠습니다. 아직 보내지 않았고, 보내기는 사용자가 메일 앱에서 직접 누릅니다. '보냈다'고 말하지 마세요.")
        } catch (_: ActivityNotFoundException) {
            ToolResult.Failure("이 휴대폰에서 메일 앱을 찾지 못했습니다.")
        }
    }
}
