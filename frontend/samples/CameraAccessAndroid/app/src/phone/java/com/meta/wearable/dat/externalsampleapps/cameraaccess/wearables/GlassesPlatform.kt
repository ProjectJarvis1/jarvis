package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope

/** Phone build: no glasses SDK in the app, so there are never glasses to find. */
object GlassesPlatform {
  const val SUPPORTED = false
  val permissions: List<String> = emptyList()

  fun initialize(context: Context) = Unit

  fun monitor(scope: CoroutineScope, app: Application, sink: GlassesSink) {
    sink.onRegistration(GlassesRegistration.UNAVAILABLE)
  }

  fun startRegistration(activity: Activity) = Unit

  fun startUnregistration(activity: Activity) = Unit

  suspend fun cameraPermissionGranted(): Result<Boolean> = Result.success(false)

  fun permissionRequester(activity: ComponentActivity): suspend () -> Boolean = { false }

  fun openCamera(scope: CoroutineScope, minFrameIntervalMs: Long, listener: GlassesCameraListener): GlassesCamera? {
    listener.onError("이 버전은 휴대폰 전용이라 글라스를 연결할 수 없어요.")
    return null
  }
}
