package com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.LocalDateTime

/**
 * Debug builds only: runs one proactive pass as if it were [EXTRA_NOW] in Korea, so the offers can be
 * checked on an emulator whose clock cannot be changed.
 *   adb shell am broadcast -n ai.jarvisglass.app/<this class> --es now 2026-11-13T10:00
 */
class ProactiveDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val now = intent.getStringExtra("now")?.let {
            LocalDateTime.parse(it).atZone(ProactivePlanner.KST).toInstant().toEpochMilli()
        } ?: System.currentTimeMillis()
        val pending = goAsync()
        Thread {
            try {
                val sent = ProactiveWorker.runOnce(context, now)
                Log.i("ProactiveDebug", "sent ${sent.size}: ${sent.joinToString(" | ") { it.message }}")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
