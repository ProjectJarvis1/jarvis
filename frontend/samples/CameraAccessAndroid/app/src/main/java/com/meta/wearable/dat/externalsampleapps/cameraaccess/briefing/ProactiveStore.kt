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

    private fun file(context: Context) = File(JarvisAccount.dataDir(context), FILE)

    @Synchronized
    fun load(context: Context) {
        val uid = JarvisAccount.userId
        if (loadedFor == uid) return
        claims.clear()
        val list = mutableListOf<ProactiveOffer>()
        runCatching {
            val f = file(context)
            if (f.exists()) {
                val json = JSONObject(f.readText())
                json.optJSONObject("claimed")?.let { c -> c.keys().forEach { k -> claims[k] = c.getLong(k) } }
                json.optJSONArray("offers")?.let { a -> for (i in 0 until a.length()) offerFrom(a.getJSONObject(i))?.let(list::add) }
            }
        }
        loadedFor = uid
        _offers.value = list
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
        _offers.value = (listOf(offer) + _offers.value.filter { it.id != offer.id }).take(KEEP_OFFERS)
        save(context)
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
        _offers.value = _offers.value.filter { it.id != id }
        save(context)
    }

    /** Called on sign-out: the next account starts with no offers and no claims. */
    @Synchronized
    fun clear(context: Context) {
        // Notifications already shown carry this account's event titles and names: take them down too.
        load(context)
        val shown = androidx.core.app.NotificationManagerCompat.from(context)
        _offers.value.forEach { shown.cancel(it.id.hashCode()) }
        runCatching { file(context).delete() }
        claims.clear()
        loadedFor = null
        _offers.value = emptyList()
        _opened.value = null
    }

    private fun save(context: Context) {
        val json = JSONObject()
            .put("claimed", JSONObject(claims as Map<*, *>))
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
