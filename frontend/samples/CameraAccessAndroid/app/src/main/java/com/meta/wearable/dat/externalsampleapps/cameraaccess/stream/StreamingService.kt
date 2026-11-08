package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R

/**
 * Foreground service that keeps Jarvis working when the screen is locked or the phone is in a pocket:
 * the glasses video stream, and the microphone for the "자비스" wake word and live conversation.
 *
 * It runs while any reason holds it. The conversation screen holds [REASON_LISTEN] from the moment it
 * opens until the user leaves it, so the microphone type is granted while the app is on screen
 * (Android 14 refuses to add it from the background) and a glasses stream that drops and restarts
 * in a pocket does not take the service, and the microphone, down with it.
 */
class StreamingService : Service() {

  companion object {
    private const val TAG = "StreamingService"
    private const val CHANNEL_ID = "streaming_channel"
    private const val NOTIFICATION_ID = 1001
    private const val WAKELOCK_TAG = "VisionClaw::StreamingWakeLock"
    private const val WAKELOCK_MS = 4 * 60 * 60 * 1000L
    const val REASON_STREAM = "stream"
    const val REASON_LISTEN = "listen"

    private val reasons = mutableSetOf<String>()
    @Volatile private var withMicrophone = false
    /** The service is up in the foreground; set by the service itself. */
    @Volatile private var running = false

    /**
     * Holds the service for [reason]. [microphone] (null = leave as is) asks for background microphone
     * use; change it only while the app is on screen. Returns false when the system refused the start.
     */
    fun start(context: Context, reason: String = REASON_STREAM, microphone: Boolean? = null): Boolean {
      val alreadyRunning = synchronized(reasons) {
        val micChanged = microphone != null && microphone != withMicrophone
        reasons += reason
        if (microphone != null) withMicrophone = microphone
        running && !micChanged
      }
      // Joining a running service needs no new start: a start from a pocket would have to call
      // startForeground again, and Android 14 refuses the microphone type from the background.
      if (alreadyRunning) return true
      val intent =
          Intent(context, StreamingService::class.java).apply { `package` = context.packageName }
      return try {
        context.startForegroundService(intent)
        true
      } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException, or SecurityException for the microphone type.
        Log.e(TAG, "Could not start service for $reason: ${e.message}")
        synchronized(reasons) { reasons -= reason }
        false
      }
    }

    fun stop(context: Context, reason: String = REASON_STREAM) {
      val empty = synchronized(reasons) {
        reasons -= reason
        if (reason == REASON_LISTEN) withMicrophone = false
        reasons.isEmpty().also { if (it) running = false }
      }
      if (empty) {
        context.stopService(Intent(context, StreamingService::class.java).apply { `package` = context.packageName })
      }
    }
  }

  private var wakeLock: PowerManager.WakeLock? = null
  private var currentType = 0

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "Service created")
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.d(TAG, "Service started")

    val notification = createNotification()

    val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    val microphone = withMicrophone && hasMicPermission()
    // Already in the foreground with the right microphone state: a second start (the glasses stream
    // reconnecting in a pocket) must not re-request types, because Android 14 would refuse the
    // microphone type from the background and the service would lose it.
    if (currentType != 0 && (currentType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) == microphone) {
      acquireWakeLock()
      return START_NOT_STICKY
    }
    // Most capable first. The microphone type is refused from the background, and the connected
    // device type without Bluetooth permission (phone camera mode), so fall back step by step.
    val candidates = buildList {
      if (microphone) {
        add(base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
      }
      add(base)
      add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    val granted = candidates.firstOrNull { type ->
      runCatching { startForeground(NOTIFICATION_ID, notification, type) }
          .onFailure { Log.w(TAG, "Foreground type $type refused: ${it.message}") }
          .isSuccess
    }
    if (granted == null) {
      stopSelf()
      return START_NOT_STICKY
    }
    currentType = granted
    running = true
    val micGranted = granted and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
    noteMicrophone(
        when {
          micGranted -> null
          !withMicrophone -> "백그라운드 마이크 꺼짐: 설정에서 켜면 화면이 꺼져도 들어요."
          !hasMicPermission() -> "마이크 권한이 없어 백그라운드에서 듣지 못해요."
          else -> "백그라운드 마이크를 받지 못했어요. 대화 화면을 다시 열어 주세요."
        },
    )

    acquireWakeLock()

    return START_NOT_STICKY
  }

  override fun onDestroy() {
    Log.d(TAG, "Service destroyed")
    running = false
    releaseWakeLock()
    super.onDestroy()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
          NotificationChannel(
                  CHANNEL_ID,
                  "Jarvis 연결",
                  NotificationManager.IMPORTANCE_LOW,
              )
              .apply {
                description = "글라스 연결과 '자비스' 호출 대기 중에 표시돼요"
                setShowBadge(false)
              }

      val notificationManager = getSystemService(NotificationManager::class.java)
      notificationManager.createNotificationChannel(channel)
    }
  }

  private fun createNotification(): Notification {
    val pendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
              flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Jarvis 대기 중")
        .setContentText(
            if (withMicrophone) "'자비스'라고 부르면 대답해요. 화면이 꺼져 있어도 들어요."
            else "글라스와 연결돼 있어요.",
        )
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setContentIntent(pendingIntent)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()
  }

  private fun acquireWakeLock() {
    if (wakeLock == null) {
      val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock =
          powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
          }
    }
    // Renewed on every start so a long pocket session keeps the CPU awake; capped so a missed
    // stop cannot hold it forever.
    wakeLock?.acquire(WAKELOCK_MS)
    Log.d(TAG, "WakeLock acquired")
  }

  private fun hasMicPermission() =
      checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

  private fun noteMicrophone(note: String?) {
    com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.WakeWordTrialLog.update(this) { it.copy(serviceNote = note) }
  }

  private fun releaseWakeLock() {
    wakeLock?.let {
      if (it.isHeld) {
        it.release()
        Log.d(TAG, "WakeLock released")
      }
    }
    wakeLock = null
  }
}
