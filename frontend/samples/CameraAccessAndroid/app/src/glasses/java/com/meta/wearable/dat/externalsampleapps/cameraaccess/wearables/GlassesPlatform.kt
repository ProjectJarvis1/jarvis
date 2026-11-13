package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.exifinterface.media.ExifInterface
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationError
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.WearablesException
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Glasses build: the Meta glasses SDK behind the app's own [GlassesSink] / [GlassesCamera] types. */
object GlassesPlatform {
  private const val TAG = "GlassesPlatform"
  const val SUPPORTED = true

  val permissions: List<String>
    get() = listOf(BLUETOOTH, BLUETOOTH_SCAN, BLUETOOTH_CONNECT)

  // AutoDeviceSelector picks the first available glasses. SDK 1.0 throws if it is built before
  // Wearables.initialize(), so it is created on first use.
  private val deviceSelector: DeviceSelector by lazy { AutoDeviceSelector() }

  fun initialize(context: Context) {
    Wearables.initialize(context)
  }

  fun monitor(scope: CoroutineScope, app: Application, sink: GlassesSink) {
    scope.launch { deviceSelector.activeDeviceFlow().collect { sink.onActiveDevice(it != null) } }
    scope.launch { Wearables.registrationState.collect { sink.onRegistration(it.toApp()) } }
    // Registration happens inside the Meta AI app; when it fails there, say why and show the SDK's code
    // so the cause can be looked up instead of guessed.
    scope.launch {
      Wearables.registrationErrorStream.collect { error ->
        if (error == RegistrationError.ALREADY_REGISTERED || error == RegistrationError.ALREADY_UNREGISTERED) return@collect
        sink.onError(registrationErrorMessage(error))
      }
    }
    val compatibilityJobs = mutableMapOf<DeviceIdentifier, Job>()
    scope.launch {
      Wearables.devices.collect { devices ->
        val hasMock = MockDeviceKit.getInstance(app).pairedDevices.isNotEmpty()
        sink.onDevices(devices.map { it.toString() }, hasMock)
        (compatibilityJobs.keys - devices).forEach { compatibilityJobs.remove(it)?.cancel() }
        (devices - compatibilityJobs.keys).forEach { id ->
          compatibilityJobs[id] = scope.launch {
            Wearables.devicesMetadata[id]?.collect { metadata ->
              if (metadata.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED) {
                val name = metadata.name.ifEmpty { id.toString() }
                sink.onError("글라스 '$name'의 소프트웨어 업데이트가 필요해요. Meta AI 앱에서 업데이트해 주세요. (오류 코드 DEVICE_UPDATE_REQUIRED)")
              }
            }
          }
        }
      }
    }
  }

  fun startRegistration(activity: Activity) = Wearables.startRegistration(activity)

  fun startUnregistration(activity: Activity) = Wearables.startUnregistration(activity)

  /** Whether the glasses camera permission is granted; a failure carries the text to show. */
  suspend fun cameraPermissionGranted(): Result<Boolean> {
    val result = Wearables.checkPermissionStatus(Permission.CAMERA)
    var failure: String? = null
    result.onFailure { error, _ -> failure = glassesErrorMessage(error, "글라스 카메라 권한을 확인하지 못했어요.") }
    failure?.let { return Result.failure(IllegalStateException(it)) }
    return Result.success(result.getOrNull() == PermissionStatus.Granted)
  }

  /**
   * Registers the glasses permission screen on [activity]; call while the activity is being created.
   * The returned function asks for the glasses camera permission and says whether it was granted.
   */
  fun permissionRequester(activity: ComponentActivity): suspend () -> Boolean {
    val mutex = Mutex()
    var pending: CancellableContinuation<PermissionStatus>? = null
    val launcher = activity.registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
      pending?.resume(result.getOrDefault(PermissionStatus.Denied))
      pending = null
    }
    return {
      mutex.withLock {
        suspendCancellableCoroutine { continuation ->
          pending = continuation
          continuation.invokeOnCancellation { pending = null }
          launcher.launch(Permission.CAMERA)
        } == PermissionStatus.Granted
      }
    }
  }

  /** Opens a camera session on the glasses, or returns null after telling [listener] why it could not. */
  fun openCamera(scope: CoroutineScope, minFrameIntervalMs: Long, listener: GlassesCameraListener): GlassesCamera? {
    // SDK 1.0: a DeviceSession connects the glasses; the camera is a capability added to it.
    val created =
        try {
          Wearables.createSession(deviceSelector)
        } catch (e: WearablesException) {
          Log.e(TAG, "Wearables SDK not ready", e)
          listener.onError("글라스 기능이 아직 준비되지 않았어요. 앱 권한을 모두 허용한 뒤 다시 시도해 주세요.")
          return null
        }
    created.onFailure { error, _ ->
      Log.e(TAG, "Failed to create glasses session: ${error.description}")
      listener.onError(glassesErrorMessage(error, "글라스 연결을 시작하지 못했어요. Meta AI 앱 연결을 확인해 주세요."))
    }
    val session = created.getOrNull() ?: return null
    return DatCamera(scope, session, minFrameIntervalMs, listener).also { it.start() }
  }

  private fun RegistrationState.toApp(): GlassesRegistration =
      runCatching { GlassesRegistration.valueOf(name) }.getOrDefault(GlassesRegistration.UNAVAILABLE)

  private fun registrationErrorMessage(error: RegistrationError): String {
    val guide = when (error) {
      RegistrationError.META_AI_NOT_INSTALLED -> "Meta AI 앱이 없어요. 설치하고 글라스를 페어링한 뒤 다시 눌러 주세요."
      RegistrationError.FAILED_TO_UNREGISTER -> "글라스 연결을 해제하지 못했어요. Meta AI 앱에서 직접 해제해 주세요."
      else -> "Meta AI 앱에서 연결을 마치지 못했어요. Meta AI 앱을 최신으로 업데이트하고 개발자 모드가 켜져 있는지 확인해 주세요."
    }
    return "$guide (오류 코드 ${error.name})"
  }
}

private class DatCamera(
    private val scope: CoroutineScope,
    private val session: DeviceSession,
    private val minFrameIntervalMs: Long,
    private val listener: GlassesCameraListener,
) : GlassesCamera {
  private val jobs = mutableListOf<Job>()
  private var camera: Camera? = null
  @Volatile private var stream: Stream? = null
  @Volatile private var closed = false
  private var lastFrameAt = 0L

  fun start() {
    jobs += scope.launch {
      session.errors.collect { error ->
        Log.e(TAG, "Glasses session error: ${error.description}")
        listener.onError(glassesErrorMessage(error))
      }
    }
    jobs += scope.launch {
      // Subscribed before start() so no transition is missed; the camera is added once connected.
      val ready = async { session.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED } }
      session.start()
      if (ready.await() != DeviceSessionState.STARTED || closed) return@launch
      session
          .addCamera(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 30))
          .onSuccess { added -> if (!closed) attach(added) else added.close() }
          .onFailure { error, _ ->
            Log.e(TAG, "Failed to add glasses camera: ${error.description}")
            listener.onError(glassesErrorMessage(error, "글라스 카메라를 켜지 못했어요. 카메라 권한을 확인해 주세요."))
          }
    }
  }

  private fun attach(added: Camera) {
    camera = added
    val s = added.stream
    stream = s
    jobs += scope.launch {
      s.videoStream.conflate().collect { frame ->
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < minFrameIntervalMs) return@collect
        lastFrameAt = now
        // VideoFrame holds raw I420 data in a ByteBuffer; copy it without moving the buffer's position.
        val buffer = frame.buffer
        val bytes = ByteArray(buffer.remaining())
        val position = buffer.position()
        buffer.get(bytes)
        buffer.position(position)
        listener.onFrame(bytes, frame.width, frame.height)
      }
    }
    jobs += scope.launch {
      s.errorStream.collect { error ->
        Log.e(TAG, "Glasses stream error: ${error.description}")
        listener.onError(glassesErrorMessage(error, "글라스 영상에 문제가 생겼어요."))
      }
    }
    jobs += scope.launch {
      s.state.collect { state ->
        listener.onState(runCatching { StreamState.valueOf(state.name) }.getOrDefault(StreamState.STOPPED))
      }
    }
    s.start().onFailure { error, _ ->
      Log.e(TAG, "Failed to start glasses stream: ${error.description}")
      listener.onError("글라스 영상을 시작하지 못했어요. 다시 시도해 주세요.")
    }
  }

  override suspend fun capturePhoto(): Bitmap? {
    val s = stream ?: return null
    return s.capturePhoto().getOrNull()?.let(::photoDataToBitmap)
  }

  override fun close() {
    closed = true
    jobs.forEach { it.cancel() }
    jobs.clear()
    // Closing the camera detaches the capability; otherwise the next addCamera() says "already active".
    camera?.close()
    camera = null
    stream = null
    session.stop()
  }

  private fun photoDataToBitmap(photo: PhotoData): Bitmap =
      when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> {
          val bytes = ByteArray(photo.data.remaining())
          photo.data.get(bytes)
          val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
          applyTransform(bitmap, exifTransform(bytes))
        }
      }

  private fun exifTransform(bytes: ByteArray): Matrix {
    val matrix = Matrix()
    val exif = try {
      ByteArrayInputStream(bytes).use { ExifInterface(it) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      return matrix
    }
    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    }
    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) return bitmap
    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) bitmap.recycle()
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  private companion object {
    const val TAG = "GlassesCamera"
  }
}
