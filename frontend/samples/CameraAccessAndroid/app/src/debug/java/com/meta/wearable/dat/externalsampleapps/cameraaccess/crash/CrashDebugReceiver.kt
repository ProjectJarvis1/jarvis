package com.meta.wearable.dat.externalsampleapps.cameraaccess.crash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Debug builds only: crashes the app on purpose so crash reporting can be checked end to end.
 *   adb shell am broadcast -n ai.jarvisglass.app/com.meta.wearable.dat.externalsampleapps.cameraaccess.crash.CrashDebugReceiver
 * The message carries a phone number and a sentence on purpose: neither may reach the server report.
 */
class CrashDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Handler(Looper.getMainLooper()).post {
            throw IllegalStateException("시험용 강제 종료: 김 팀장 010-1234-5678 에게 견적서 보내기")
        }
    }
}
