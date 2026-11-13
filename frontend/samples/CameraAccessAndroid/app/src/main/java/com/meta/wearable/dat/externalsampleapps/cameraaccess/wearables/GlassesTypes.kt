package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables

import android.graphics.Bitmap
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamState

/**
 * The app's own view of the glasses, so shared code never touches the glasses SDK. The "glasses" build
 * implements [GlassesPlatform] with the SDK; the "phone" build has a version that reports no glasses.
 */
enum class GlassesRegistration { UNAVAILABLE, AVAILABLE, REGISTERING, REGISTERED, UNREGISTERING }

/** Where [GlassesPlatform.monitor] reports what the SDK sees. */
interface GlassesSink {
  fun onActiveDevice(active: Boolean)
  fun onRegistration(state: GlassesRegistration)
  fun onDevices(ids: List<String>, hasMockDevices: Boolean)
  /** Korean text for the user, with the SDK code when there is one. */
  fun onError(message: String)
}

/** One open camera session on the glasses. */
interface GlassesCamera {
  /** A full-resolution photo from the glasses, or null when none came back. */
  suspend fun capturePhoto(): Bitmap?
  fun close()
}

interface GlassesCameraListener {
  fun onState(state: StreamState)
  /** A raw I420 frame, already thinned to the interval asked for in [GlassesPlatform.openCamera]. */
  fun onFrame(i420: ByteArray, width: Int, height: Int)
  fun onError(message: String)
}
