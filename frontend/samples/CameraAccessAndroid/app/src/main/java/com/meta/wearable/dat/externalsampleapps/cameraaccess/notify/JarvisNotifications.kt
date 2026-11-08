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
