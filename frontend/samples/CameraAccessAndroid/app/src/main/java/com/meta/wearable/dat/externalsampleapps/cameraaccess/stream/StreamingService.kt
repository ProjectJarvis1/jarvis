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

    /**
     * Foreground types to try, most capable first. Listening and the glasses stream run for hours, so no
     * time-limited type (dataSync stops after six background hours on Android 15 and would crash the app):
     * the microphone for "자비스" and live talk, the connected-device type for the glasses link. The phone
     * build has no glasses, so without the microphone it has nothing to keep running.
     */
    fun candidateTypes(glasses: Boolean, microphone: Boolean): List<Int> = buildList {
      val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
      val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
      if (microphone) {
        if (glasses) add(device or mic)
        add(mic) // the connected-device type is refused without Bluetooth permission (phone camera mode)
      }
      if (glasses) add(device)
    }
    @Volatile private var withMicrophone = false
    /** The service is up in the foreground; set by the service itself. */
    @Volatile private var running = false
    /** The running service actually holds the microphone type (asking is not getting). */
    @Volatile private var micGranted = false

    /**
     * Holds the service for [reason]. [microphone] (null = leave as is) asks for background microphone
     * use; change it only while the app is on screen. Returns false when the system refused the start.
     */
    fun start(context: Context, reason: String = REASON_STREAM, microphone: Boolean? = null): Boolean {
      val alreadyRunning = synchronized(reasons) {
        val micChanged = microphone != null && microphone != withMicrophone
        // A visible caller may retry a microphone request that the last start could not fulfil
        // (permission granted since, or the first start happened from the background).
        val micRetry = microphone == true && !micGranted
        reasons += reason
        if (microphone != null) withMicrophone = microphone
        running && !micChanged && !micRetry
      }
      if (!alreadyRunning) {
        val micOk = withMicrophone && context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (candidateTypes(com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig.GLASSES, micOk).isEmpty()) {
          synchronized(reasons) { reasons -= reason }
          return false
        }
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
        reasons.isEmpty().also { if (it) { running = false; micGranted = false } }
      }
      val intent = Intent(context, StreamingService::class.java).apply { `package` = context.packageName }
      if (empty) {
        context.stopService(intent)
      } else if (reason == REASON_LISTEN && micGranted) {
        // Still held for the glasses stream: start again so it drops the microphone type.
        runCatching { context.startForegroundService(intent) }
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

    val glasses = com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig.GLASSES
    val microphone = withMicrophone && hasMicPermission()
    // Already in the foreground with the right microphone state: a second start (the glasses stream
    // reconnecting in a pocket) must not re-request types, because Android 14 would refuse the
    // microphone type from the background and the service would lose it.
    if (currentType != 0 && (currentType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) == microphone) {
      acquireWakeLock()
      return START_NOT_STICKY
    }
    val candidates = candidateTypes(glasses, microphone)
    val granted = candidates.firstOrNull { type ->
      val notification = createNotification(type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
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
    micGranted = granted and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
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
    micGranted = false
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

  private fun createNotification(listening: Boolean): Notification {
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
            when {
              listening -> "'자비스'라고 부르면 대답해요. 화면이 꺼져 있어도 들어요."
              withMicrophone -> "백그라운드 듣기를 시작하지 못했어요. 앱에서 마이크 권한을 확인해 주세요."
              else -> "글라스와 연결돼 있어요."
            },
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
