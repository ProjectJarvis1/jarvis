package com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing

import com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConfig
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Finds meetings about to start in the phone's calendar and prepares their briefings. Reads the
 * calendar on the phone and searches only Jarvis's own server, so it adds no paid API call.
 */
object MeetingBriefer {
    private const val TAG = "MeetingBriefer"
    private const val PREFS = "meeting_briefing"
    private val client = OkHttpClient.Builder().addInterceptor(JarvisAccount.sessionGuard).connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    @Volatile private var dedupe: BriefingDedupe? = null

    fun hasCalendarPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /**
     * Briefings for meetings that are now 5 to 12 minutes away and were not delivered yet. Call off the
     * main thread, then [markDelivered] each one once its notification is posted: a poll cancelled midway
     * (the screen closed during a slow search) leaves the meeting to be briefed on the next poll.
     */
    fun due(context: Context, nowMs: Long = System.currentTimeMillis()): List<Pair<UpcomingMeeting, Brief>> {
        if (!hasCalendarPermission(context)) return emptyList()
        val delivered = dedupe(context)
        return upcoming(context, nowMs)
            .filter { MeetingBriefing.isDue(it, nowMs) && !delivered.contains(it.key, nowMs) }
            .map { meeting -> meeting to brief(meeting) }
    }

    fun markDelivered(context: Context, key: String) {
        val delivered = dedupe(context)
        if (delivered.claim(key, System.currentTimeMillis())) save(context, delivered)
    }

    /**
     * Calendar occurrences from [fromMs] to [toMs], all-day ones included, skipping canceled and declined
     * ones. Used by the proactive offers, which look days ahead for deadlines.
     */
    fun calendarItems(context: Context, fromMs: Long, toMs: Long): List<CalendarItem> {
        if (!hasCalendarPermission(context)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS, CalendarContract.Instances.OWNER_ACCOUNT,
        )
        val result = mutableListOf<CalendarItem>()
        runCatching {
            context.contentResolver.query(uri, projection, null, null, CalendarContract.Instances.BEGIN)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(5) == CalendarContract.Events.STATUS_CANCELED) continue
                    if (c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val eventId = c.getLong(0)
                    val begin = c.getLong(1)
                    val allDay = c.getInt(4) == 1
                    result += CalendarItem(
                        key = "$eventId:$begin",
                        title = c.getString(2).orEmpty().ifBlank { "제목 없는 일정" },
                        beginMs = begin,
                        allDay = allDay,
                        location = c.getString(3).orEmpty(),
                        attendees = if (allDay) emptyList() else attendees(context, eventId, c.getString(7).orEmpty()),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Calendar read failed: ${it.message}") }
        return result
    }

    /** Counts failed searches, so a caller can tell "nothing stored" from "the server did not answer". */
    @Volatile private var failedSearches = 0

    /** The briefing for [meeting], or null when any search failed (try again later). Call off the main thread. */
    internal fun briefFor(meeting: UpcomingMeeting): Brief? {
        val before = failedSearches
        val brief = brief(meeting)
        return brief.takeIf { failedSearches == before }
    }

    /**
     * Stored people, meetings and notes that may be about a deadline, searched by its distinctive words;
     * null when any search failed, so an empty answer is never mistaken for "nothing stored".
     */
    internal fun recordsFor(words: List<String>, title: String): Triple<List<PersonHit>, List<MeetingHit>, List<MemoryHit>>? {
        val before = failedSearches
        val query = (words.ifEmpty { listOf(title) }).joinToString(" ")
        val found = Triple(words.take(3).flatMap { searchPeople(it) }, searchMeetings(query), searchMemories(query))
        return found.takeIf { failedSearches == before }
    }

    private fun brief(meeting: UpcomingMeeting): Brief {
        val keys = MeetingBriefing.keys(meeting)
        val query = (listOf(meeting.title) + meeting.attendees).joinToString(" ").trim()
        val people = keys.take(4).flatMap { key -> searchPeople(key) }
        val meetings = if (query.isBlank()) emptyList() else searchMeetings(query)
        val memories = if (query.isBlank()) emptyList() else searchMemories(query)
        // "N분 뒤" counted after the searches, not from when the poll began.
        val brief = MeetingBriefing.compose(meeting, people, meetings, memories, System.currentTimeMillis())
        // Recent mail with the same people (Gmail linked only). A failed mail check leaves the brief as it was.
        // Only people and companies, never generic title words ("견적" alone would pull in every quote mail).
        val names = (brief.people + meeting.attendees.map { it.trim().removeSuffix("님").trim() }
            .filter { it.length >= 2 && '@' !in it }).distinct()
        val mails = MailBriefer.context(names, brief.orgs)?.map { MailHit(it.sender, it.subject, it.date, it.snippet) }.orEmpty()
        return MeetingBriefing.withMail(brief, mails)
    }

    /** Timed events starting in the next 15 minutes, skipping all-day, canceled and declined ones. */
    private fun upcoming(context: Context, nowMs: Long): List<UpcomingMeeting> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, nowMs)
            ContentUris.appendId(it, nowMs + 15 * 60_000L)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS, CalendarContract.Instances.OWNER_ACCOUNT,
        )
        val result = mutableListOf<UpcomingMeeting>()
        runCatching {
            context.contentResolver.query(uri, projection, null, null, CalendarContract.Instances.BEGIN)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(4) == 1) continue
                    if (c.getInt(5) == CalendarContract.Events.STATUS_CANCELED) continue
                    if (c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val eventId = c.getLong(0)
                    val begin = c.getLong(1)
                    val owner = c.getString(7).orEmpty()
                    result += UpcomingMeeting(
                        key = "$eventId:$begin",
                        title = c.getString(2).orEmpty().ifBlank { "제목 없는 일정" },
                        beginMs = begin,
                        location = c.getString(3).orEmpty(),
                        attendees = attendees(context, eventId, owner),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Calendar read failed: ${it.message}") }
        return result
    }

    /** Attendee names, without the user and without those who declined. An email-only attendee has no usable name. */
    private fun attendees(context: Context, eventId: Long, owner: String): List<String> {
        val names = mutableListOf<String>()
        runCatching {
            CalendarContract.Attendees.query(context.contentResolver, eventId,
                arrayOf(CalendarContract.Attendees.ATTENDEE_NAME, CalendarContract.Attendees.ATTENDEE_EMAIL,
                    CalendarContract.Attendees.ATTENDEE_STATUS))?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0).orEmpty().trim()
                    val email = c.getString(1).orEmpty().trim()
                    // Someone who declined is not coming; do not brief on them.
                    if (c.getInt(2) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    if (owner.isNotBlank() && email.equals(owner, ignoreCase = true)) continue
                    if (name.isNotBlank() && '@' !in name) names += name
                }
            }
        }
        return names.distinct()
    }

    private fun searchPeople(query: String): List<PersonHit> = post("/people/search", JSONObject().put("query", query).put("top_k", 3))
        ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optJSONObject("person") } }
        ?.map { p ->
            val aliases = p.optJSONArray("aliases")
            PersonHit(p.optString("name"), p.optNullable("org"), p.optNullable("role"), p.optNullable("last_met_at"),
                p.optNullable("notes_summary"), (0 until (aliases?.length() ?: 0)).mapNotNull { aliases?.optString(it)?.takeIf(String::isNotBlank) })
        }.orEmpty()

    private fun searchMeetings(query: String): List<MeetingHit> = post("/meetings/search", JSONObject().put("query", query).put("top_k", 5))
        ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
        ?.map { m ->
            val metadata = m.optJSONObject("metadata") ?: JSONObject()
            val items = metadata.optJSONArray("action_items")
            MeetingHit(m.optString("title"), m.optNullable("started_at"), m.optNullable("summary"),
                (0 until (items?.length() ?: 0)).mapNotNull { items?.optString(it)?.takeIf(String::isNotBlank) })
        }.orEmpty()

    private fun searchMemories(query: String): List<MemoryHit> = post("/memory/search", JSONObject().put("query", query).put("top_k", 5))
        ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optJSONObject("memory") } }
        ?.map { m ->
            val metadata = m.optJSONObject("metadata") ?: JSONObject()
            MemoryHit(m.optString("text"), metadata.optNullable("people_text"), m.optNullable("captured_at"))
        }.orEmpty()

    /** A failed search leaves that part of the brief empty instead of failing the whole brief. */
    private fun post(path: String, body: JSONObject): JSONArray? = runCatching {
        val request = Request.Builder()
            .url("${GeminiConfig.jarvisApiBase.trimEnd('/')}$path")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", GeminiConfig.jarvisAuthorization)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            JSONArray(response.body?.string().orEmpty())
        }
    }.onFailure { failedSearches++; Log.w(TAG, "Search $path failed: ${it.message}") }.getOrNull()

    private fun JSONObject.optNullable(key: String): String = if (isNull(key)) "" else optString(key)

    private fun dedupe(context: Context): BriefingDedupe = dedupe ?: synchronized(this) {
        dedupe ?: run {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            BriefingDedupe(p.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()).also { dedupe = it }
        }
    }

    private fun save(context: Context, claimed: BriefingDedupe) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        claimed.snapshot().forEach { (k, v) -> edit.putLong(k, v) }
        edit.apply()
    }
}
