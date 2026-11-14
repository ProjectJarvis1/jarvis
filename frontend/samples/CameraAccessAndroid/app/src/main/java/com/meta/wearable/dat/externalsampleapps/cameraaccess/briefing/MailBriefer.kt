package com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing

import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConfig
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Asks Jarvis's own server about mail and follow-ups for the proactive offers, the meeting briefing and
 * the morning briefing. The server reads Gmail on the spot and keeps nothing of it; no paid AI is called.
 * Every call returns null when the server did not answer, so a caller can retry on a later run.
 */
object MailBriefer {
    private const val TAG = "MailBriefer"
    private val client = OkHttpClient.Builder().addInterceptor(JarvisAccount.sessionGuard)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    data class NewMail(val connected: Boolean, val checkedUntilMs: Long, val mails: List<MailCandidate>)
    data class MailLine(val sender: String, val subject: String, val date: String, val snippet: String)

    /** Business mails that arrived after [afterMs]; connected=false when Gmail is not linked. */
    fun newMail(afterMs: Long): NewMail? = get("/briefing/mail/new?after_ms=$afterMs")?.let { json ->
        if (json.has("error")) return null  // Gmail quota or a server-side setup problem: try again later
        NewMail(json.optBoolean("connected"), json.optLong("checked_until_ms", afterMs), parseMails(json.optJSONArray("mails")))
    }

    /**
     * Recent mail with the people of a meeting; empty when Gmail is not linked. [people] are saved people the
     * briefing already told apart (name, company): the server looks them up by their saved mail address.
     * [names] are attendees with no saved record, looked up by name.
     */
    fun context(people: List<Pair<String, String>>, names: List<String>): List<MailLine>? {
        if (people.isEmpty() && names.isEmpty()) return emptyList()
        val body = JSONObject()
            .put("people", JSONArray(people.take(10).map { (name, org) -> JSONObject().put("name", name).put("org", org) }))
            .put("names", JSONArray(names.take(10))).put("limit", 3)
        val json = post("/briefing/mail/context", body) ?: return null
        val arr = json.optJSONArray("mails") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            MailLine(it.optString("sender"), it.optString("subject"), it.optString("date"), it.optString("snippet"))
        }
    }

    fun followups(): List<FollowupItem>? = get("/briefing/followups")?.optJSONArray("items")?.let { parseFollowups(it) }

    internal fun parseFollowups(arr: JSONArray): List<FollowupItem> = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
        FollowupItem(it.optString("task"), it.optString("due_text"), it.optString("meeting_title"), it.optString("meeting_date"),
            it.optString("meeting_id"), it.optInt("index", -1), it.optString("due_at"))
    }

    internal fun parseMails(arr: JSONArray?): List<MailCandidate> = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
        val o = arr?.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val prep = o.optJSONArray("prep")
        MailCandidate(id, o.optString("subject"), o.optString("sender"), o.optLong("date_ms"), o.optString("message"),
            (0 until (prep?.length() ?: 0)).mapNotNull { prep?.optString(it)?.takeIf(String::isNotBlank) })
    }

    private fun get(path: String): JSONObject? = call(Request.Builder().url(url(path)).get(), path)
    private fun post(path: String, body: JSONObject): JSONObject? =
        call(Request.Builder().url(url(path)).post(body.toString().toRequestBody("application/json".toMediaType())), path)

    private fun url(path: String) = "${GeminiConfig.jarvisApiBase.trimEnd('/')}$path"

    private fun call(builder: Request.Builder, path: String): JSONObject? = runCatching {
        if (!GeminiConfig.isJarvisConfigured) error("not signed in")
        client.newCall(builder.addHeader("Authorization", GeminiConfig.jarvisAuthorization).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            JSONObject(r.body?.string().orEmpty())
        }
    }.onFailure { Log.w(TAG, "$path failed: ${it.message}") }.getOrNull()
}
