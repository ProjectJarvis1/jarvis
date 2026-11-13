package com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount
import com.meta.wearable.dat.externalsampleapps.cameraaccess.notify.JarvisNotifications
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every 15 minutes, even with the app closed: reads the phone calendar and, when it is time, offers to
 * prepare the day's meeting or an upcoming deadline. Reads the calendar on the phone and searches only
 * Jarvis's own server, so it adds no paid API call. Nothing is sent outside 08:00 to 21:00 KST.
 */
class ProactiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { runOnce(applicationContext, System.currentTimeMillis()) { !isStopped } }
            .onFailure { Log.w(TAG, "Proactive check failed: ${it.message}") }
        Result.success()
    }

    companion object {
        private const val TAG = "ProactiveWorker"
        private const val WORK = "jarvis-proactive"

        /** Starts (or keeps) the periodic check; stops it when the setting is off. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context.applicationContext)
            if (!SettingsManager.proactiveOffersEnabled) {
                wm.cancelUniqueWork(WORK)
                return
            }
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ProactiveWorker>(15, TimeUnit.MINUTES).build())
        }

        /** One pass. Returns the offers that reached the user (notification shown or kept in the list). */
        fun runOnce(context: Context, nowMs: Long, stillWanted: () -> Boolean = { true }): List<ProactiveOffer> {
            val app = context.applicationContext
            SettingsManager.init(app)
            JarvisAccount.init(app)
            if (!JarvisAccount.isSignedIn || !SettingsManager.proactiveOffersEnabled) return emptyList()
            if (!MeetingBriefer.hasCalendarPermission(app)) return emptyList()
            if (!ProactivePlanner.withinHours(nowMs)) return emptyList()
            val uid = JarvisAccount.userId
            // [nowMs] may be a chosen time (debug); the time spent searching is added to it.
            val startedAt = System.currentTimeMillis()
            fun current() = nowMs + (System.currentTimeMillis() - startedAt)

            val items = MeetingBriefer.calendarItems(app, nowMs - 24 * 3_600_000L, ProactivePlanner.windowEnd(nowMs))
            val sent = mutableListOf<ProactiveOffer>()
            for (item in items) {
                // The account may have changed or signed out while the server was searched.
                if (JarvisAccount.userId != uid || !JarvisAccount.isSignedIn) break
                val claimed = ProactiveStore.claimed(app, nowMs)
                if (ProactivePlanner.sentToday(claimed, nowMs) >= ProactivePlanner.MAX_PER_DAY) break
                val offer = when {
                    ProactivePlanner.meetingDue(item, nowMs) && ProactivePlanner.meetingKey(item) !in claimed ->
                        meetingOffer(item, nowMs)?.let { ProactivePlanner.meetingKey(item) to it }
                    else -> ProactivePlanner.deadlineStage(item, nowMs)?.let { stage ->
                        val key = ProactivePlanner.deadlineKey(item, stage)
                        if (key in claimed) null else deadlineOffer(item, stage, nowMs)?.let { key to it }
                    }
                } ?: continue
                val (key, o) = offer
                // The search takes time: the switch may be off now, the work cancelled, or the account changed.
                // Slow searches can also run past 21:00; what is left waits, unclaimed, for the next allowed pass.
                if (!stillWanted() || !SettingsManager.proactiveOffersEnabled || !JarvisAccount.isSignedIn ||
                    JarvisAccount.userId != uid || !ProactivePlanner.withinHours(current())) break
                ProactiveStore.record(app, key, o)
                JarvisNotifications.postOffer(app, o)
                sent += o
            }
            return sent
        }

        /** Offered only when stored records are about this meeting; otherwise there is nothing to sum up. */
        private fun meetingOffer(item: CalendarItem, nowMs: Long): ProactiveOffer? {
            // Null (a search failed) is retried on the next run; nothing stored means nothing to offer.
            val brief = MeetingBriefer.briefFor(item.asMeeting()) ?: return null
            if (!brief.hasRecords) return null
            // The brief's first line says "N분 뒤", which is stale by the time the offer is opened.
            val detail = (listOf("오늘 ${ProactivePlanner.clock(item.beginMs)} ${item.title}" +
                (if (item.location.isNotBlank()) " (${item.location})" else "")) + brief.detail.lines().drop(1)).joinToString("\n")
            return ProactiveOffer("meet-${item.key}", OfferKind.MEETING, item.title,
                ProactivePlanner.meetingMessage(item, brief.people, brief.hasHistory, brief.historyWith), detail, nowMs, item.beginMs)
        }

        /** Null when the server did not answer: offered on a later run instead of as "nothing stored". */
        private fun deadlineOffer(item: CalendarItem, stage: DeadlineStage, nowMs: Long): ProactiveOffer? {
            val (people, meetings, memories) = MeetingBriefer.recordsFor(ProactivePlanner.deadlineWords(item), item.title) ?: return null
            return ProactiveOffer("due-${item.key}-${stage.name}", OfferKind.DEADLINE, item.title,
                ProactivePlanner.deadlineMessage(item, nowMs),
                ProactivePlanner.deadlineDetail(item, people, meetings, memories), nowMs, item.beginMs)
        }
    }
}
