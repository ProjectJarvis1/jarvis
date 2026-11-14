package com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing

import android.content.Context
import com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The offers Jarvis made on its own and which occurrences were already offered, kept in the signed-in
 * user's own folder so another account on the phone never sees them. Logging out deletes it.
 */
object ProactiveStore {
    private const val FILE = "proactive.json"
    private const val KEEP_CLAIMS_MS = 10 * 24 * 3_600_000L
    private const val KEEP_OFFERS = 10

    private val _offers = MutableStateFlow<List<ProactiveOffer>>(emptyList())
    /** Latest offers first, for the home screen. */
    val offers: StateFlow<List<ProactiveOffer>> = _offers

    private val _opened = MutableStateFlow<ProactiveOffer?>(null)
    /** The offer the user just accepted (from a notification or the list); the app shows its detail. */
    val opened: StateFlow<ProactiveOffer?> = _opened

    private var loadedFor: String? = null
    private val claims = mutableMapOf<String, Long>()
    /** Mail that arrived up to this time was already checked for offers; null before the first check. */
    private var mailAfter: Long? = null
    /** Notification ids posted for this account, kept apart from the list so a dismissed or dropped offer is still taken down on sign-out. */
    private val posted = linkedSetOf<Int>()
    private const val KEEP_POSTED = 64

    private fun file(context: Context) = File(JarvisAccount.dataDir(context), FILE)

    /** The notification id of [offer]; the same id posts, replaces and cancels it. */
    fun notificationId(offer: ProactiveOffer) = offer.id.hashCode()

    @Synchronized
    fun load(context: Context) {
        val uid = JarvisAccount.userId
        if (loadedFor == uid) return
        claims.clear()
        posted.clear()
        mailAfter = null
        val list = mutableListOf<ProactiveOffer>()
        runCatching {
            val f = file(context)
            if (f.exists()) {
                val json = JSONObject(f.readText())
                json.optJSONObject("claimed")?.let { c -> c.keys().forEach { k -> claims[k] = c.getLong(k) } }
                json.optJSONArray("posted")?.let { a -> for (i in 0 until a.length()) posted += a.getInt(i) }
                if (json.has("mailAfter")) mailAfter = json.getLong("mailAfter")
                json.optJSONArray("offers")?.let { a -> for (i in 0 until a.length()) offerFrom(a.getJSONObject(i))?.let(list::add) }
            }
        }
        loadedFor = uid
        // One card per event, also for lists saved before cards were merged (newest first, so the latest stays).
        _offers.value = list.distinctBy { Triple(it.kind, it.eventBeginMs, it.title) }
    }

    @Synchronized
    fun claimed(context: Context, nowMs: Long): Map<String, Long> {
        load(context)
        claims.entries.removeIf { nowMs - it.value > KEEP_CLAIMS_MS }
        return claims.toMap()
    }

    /** Records [offer] as sent under [key] (once per occurrence and stage) and adds it to the list. */
    @Synchronized
    fun record(context: Context, key: String, offer: ProactiveOffer) {
        load(context)
        claims[key] = offer.createdAtMs
        posted += notificationId(offer)
        while (posted.size > KEEP_POSTED) posted.remove(posted.first())
        // One card per event: the day-before offer replaces the earlier one for the same deadline.
        fun sameEvent(o: ProactiveOffer) = o.id == offer.id ||
            (o.kind == offer.kind && o.eventBeginMs == offer.eventBeginMs && o.title == offer.title)
        val replaced = _offers.value.filter { sameEvent(it) && it.id != offer.id }
        if (replaced.isNotEmpty()) {
            val shown = androidx.core.app.NotificationManagerCompat.from(context)
            replaced.forEach { shown.cancel(notificationId(it)) }
        }
        _offers.value = (listOf(offer) + _offers.value.filterNot(::sameEvent)).take(KEEP_OFFERS)
        save(context)
    }

    /**
     * Records and shows [offer] only if [uid] is still the signed-in account, all under this store's lock,
     * which sign-out also holds while it clears: an offer can never land in another account's folder, nor
     * be posted after the sign-out cleanup took the notifications down.
     */
    @Synchronized
    fun recordAndPost(context: Context, uid: String, key: String, offer: ProactiveOffer, post: () -> Unit): Boolean {
        if (!JarvisAccount.isSignedIn || JarvisAccount.userId != uid) return false
        record(context, key, offer)
        post()
        return true
    }

    /** Where the next mail check starts; the first check starts [firstLookbackMs] back, not at old mail. */
    @Synchronized
    fun mailCursor(context: Context, nowMs: Long, firstLookbackMs: Long = 3_600_000L): Long {
        load(context)
        return mailAfter ?: (nowMs - firstLookbackMs)
    }

    /** Moves the mail cursor forward (never back), for [uid] only, so a check from a signed-out account changes nothing. */
    @Synchronized
    fun advanceMail(context: Context, uid: String, untilMs: Long) {
        if (!JarvisAccount.isSignedIn || JarvisAccount.userId != uid) return
        load(context)
        if (untilMs > (mailAfter ?: Long.MIN_VALUE)) {
            mailAfter = untilMs
            save(context)
        }
    }

    @Synchronized
    fun find(context: Context, id: String): ProactiveOffer? {
        load(context)
        return _offers.value.firstOrNull { it.id == id }
    }

    fun open(offer: ProactiveOffer?) { _opened.value = offer }

    @Synchronized
    fun dismiss(context: Context, id: String) {
        load(context)
        _offers.value.firstOrNull { it.id == id }?.let {
            androidx.core.app.NotificationManagerCompat.from(context).cancel(notificationId(it))
        }
        _offers.value = _offers.value.filter { it.id != id }
        save(context)
    }

    /** Called on sign-out: the next account starts with no offers and no claims. */
    @Synchronized
    fun clear(context: Context) {
        // Notifications already shown carry this account's event titles and names: take them all down,
        // including those whose offer was dismissed or dropped from the list.
        load(context)
        val shown = androidx.core.app.NotificationManagerCompat.from(context)
        (posted + _offers.value.map(::notificationId)).forEach { shown.cancel(it) }
        runCatching { file(context).delete() }
        claims.clear()
        posted.clear()
        mailAfter = null
        loadedFor = null
        _offers.value = emptyList()
        _opened.value = null
    }

    private fun save(context: Context) {
        val json = JSONObject()
            .put("claimed", JSONObject(claims as Map<*, *>))
            .put("posted", JSONArray(posted.toList()))
            .also { j -> mailAfter?.let { j.put("mailAfter", it) } }
            .put("offers", JSONArray(_offers.value.map { o ->
                JSONObject().put("id", o.id).put("kind", o.kind.name).put("title", o.title).put("message", o.message)
                    .put("detail", o.detail).put("created", o.createdAtMs).put("begin", o.eventBeginMs)
            }))
        runCatching {
            val f = file(context)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(f)
        }
    }

    private fun offerFrom(o: JSONObject): ProactiveOffer? = runCatching {
        ProactiveOffer(o.getString("id"), OfferKind.valueOf(o.getString("kind")), o.getString("title"),
            o.getString("message"), o.getString("detail"), o.getLong("created"), o.getLong("begin"))
    }.getOrNull()
}
