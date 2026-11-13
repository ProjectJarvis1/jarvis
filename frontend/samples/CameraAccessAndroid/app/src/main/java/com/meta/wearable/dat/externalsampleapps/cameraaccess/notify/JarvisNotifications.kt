package com.meta.wearable.dat.externalsampleapps.cameraaccess.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R

/** Phone notifications for things Jarvis prepared on its own: meeting briefings and meeting follow-ups. */
object JarvisNotifications {
    private const val CHANNEL_ID = "jarvis_assist"
    private const val OFFER_CHANNEL_ID = "jarvis_offers"
    /** Intent extra carrying the id of an offer to open; read by MainActivity. */
    const val EXTRA_OFFER_ID = "jarvis.proactive.offer_id"

    /**
     * An offer Jarvis makes on its own ("정리해 드릴까요?"). Tapping it or its button opens the app on the
     * offer's detail. False when nothing could be shown; the offer still waits in the app's home list.
     */
    fun postOffer(context: Context, offer: com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.ProactiveOffer): Boolean {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(OFFER_CHANNEL_ID, "먼저 알려 주기", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "오늘 미팅과 다가오는 제출·마감 일정을 먼저 챙겨 줘요" },
        )
        fun opener(code: Int) = PendingIntent.getActivity(
            app, code,
            Intent(app, MainActivity::class.java).putExtra(EXTRA_OFFER_ID, offer.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val id = com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.ProactiveStore.notificationId(offer)
        val action = if (offer.kind == com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.OfferKind.MEETING) "정리해 줘" else "모아 줘"
        val notification = NotificationCompat.Builder(app, OFFER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (offer.kind == com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.OfferKind.MEETING) "오늘 미팅" else "다가오는 마감")
            .setContentText(offer.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(offer.message))
            .setContentIntent(opener(id))
            .addAction(0, action, opener(id xor 0x5A5A))
            .setAutoCancel(true)
            // Taken down once the event is over, so yesterday's "오늘 오후 2시" never lingers.
            .setTimeoutAfter((com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.ProactivePlanner.expiresAt(offer) -
                System.currentTimeMillis()).coerceAtLeast(60_000L))
            .build()
        val compat = NotificationManagerCompat.from(app)
        val channelOn = manager.getNotificationChannel(OFFER_CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        if (!compat.areNotificationsEnabled() || !channelOn) return false
        return try {
            compat.notify(id, notification)
            true
        } catch (e: SecurityException) {
            Log.w("JarvisNotifications", "Notification permission missing: ${e.message}")
            false
        }
    }

    /** False when nothing could be shown (notifications off for the app or this channel). */
    fun post(context: Context, id: Int, title: String, text: String): Boolean {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "미팅 브리핑과 후속 할 일", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "미팅 전 브리핑과 회의 뒤 할 일을 알려 줘요" },
        )
        val open = PendingIntent.getActivity(
            app, id,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val compat = NotificationManagerCompat.from(app)
        val channelOn = manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        if (!compat.areNotificationsEnabled() || !channelOn) return false
        return try {
            compat.notify(id, notification)
            true
        } catch (e: SecurityException) {
            Log.w("JarvisNotifications", "Notification permission missing: ${e.message}")
            false
        }
    }
}
