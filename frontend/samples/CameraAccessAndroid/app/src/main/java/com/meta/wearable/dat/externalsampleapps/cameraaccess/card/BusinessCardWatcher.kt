package com.meta.wearable.dat.externalsampleapps.cameraaccess.card

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads camera frames on the phone (ML Kit, on-device, no network) about once a second and reports a
 * business card once it has been seen the same way twice. Frames arriving while a read is running are
 * dropped, so a slow phone reads less often instead of falling behind.
 */
class BusinessCardWatcher(
    private val enabled: () -> Boolean,
    private val onCard: (BusinessCardText.Card, Bitmap) -> Unit,
    private val intervalMs: Long = 1_000L,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var lastReadAt = 0L
    private val sightings = CardSightings()
    private val recognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    fun offer(bitmap: Bitmap) {
        if (!enabled()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReadAt < intervalMs || !busy.compareAndSet(false, true)) return
        lastReadAt = now
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: run { busy.set(false); return }
        executor.execute {
            try {
                val text = Tasks.await(recognizer.process(InputImage.fromBitmap(copy, 0)), 3, TimeUnit.SECONDS).text
                val card = sightings.onFrame(BusinessCardText.parse(text), SystemClock.elapsedRealtime())
                if (card != null && enabled()) onCard(card, copy)
            } catch (e: Exception) {
                Log.w(TAG, "Card text read failed: ${e.message}")
            } finally {
                busy.set(false)
            }
        }
    }

    /** The offer was spoken; keep this card quiet for ten minutes. */
    fun markOffered(card: BusinessCardText.Card) = sightings.markOffered(card, SystemClock.elapsedRealtime())

    fun close() {
        executor.shutdownNow()
        runCatching { recognizer.close() }
    }

    private companion object { const val TAG = "BusinessCardWatcher" }
}
