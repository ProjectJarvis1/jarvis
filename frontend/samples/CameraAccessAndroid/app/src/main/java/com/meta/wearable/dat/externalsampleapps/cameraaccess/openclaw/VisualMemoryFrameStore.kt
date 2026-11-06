package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

object VisualMemoryFrameStore {
    private const val JPEG_QUALITY = 82
    private const val STILL_JPEG_QUALITY = 94
    private const val MAX_FRESH_FRAME_AGE_MS = 1_200L

    data class VisualFrame(
        val base64: String,
        val capturedAtMs: Long,
        val ageMs: Long,
        val source: String,
        val width: Int,
        val height: Int,
        val jpegBytes: Int,
    )

    @Volatile
    private var latestJpeg: ByteArray? = null

    @Volatile
    private var latestCapturedAtMs: Long = 0L

    @Volatile
    private var latestWidth: Int = 0

    @Volatile
    private var latestHeight: Int = 0

    /** False when the latest frame is too dark or featureless to show anything (lens covered, phone face down). */
    @Volatile
    var latestUsable: Boolean = false
        private set

    @Volatile
    var freshStillProvider: (suspend () -> VisualFrame?)? = null

    fun update(bitmap: Bitmap) {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        latestJpeg = output.toByteArray()
        latestCapturedAtMs = System.currentTimeMillis()
        latestWidth = bitmap.width
        latestHeight = bitmap.height
        latestUsable = looksUsable(bitmap)
    }

    /** Samples a 24x24 grid: mean brightness and spread. A covered lens is dark and flat. */
    internal fun looksUsable(bitmap: Bitmap): Boolean {
        val w = bitmap.width; val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        var sum = 0.0; var sumSq = 0.0; var n = 0
        for (gy in 0 until 24) for (gx in 0 until 24) {
            val c = bitmap.getPixel((gx * 2 + 1) * w / 48, (gy * 2 + 1) * h / 48)
            val y = 0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255)
            sum += y; sumSq += y * y; n++
        }
        val mean = sum / n
        val std = kotlin.math.sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
        return isUsable(mean, std)
    }

    internal fun isUsable(meanLuma: Double, stdLuma: Double): Boolean = meanLuma >= 28.0 && stdLuma >= 9.0

    fun latestBase64(): String? {
        val jpeg = latestJpeg ?: return null
        return Base64.encodeToString(jpeg, Base64.NO_WRAP)
    }

    fun latestCapturedAtMs(): Long = latestCapturedAtMs

    fun latestAgeMs(nowMs: Long = System.currentTimeMillis()): Long? {
        if (latestJpeg == null || latestCapturedAtMs == 0L) return null
        return nowMs - latestCapturedAtMs
    }

    fun isLatestFresh(nowMs: Long = System.currentTimeMillis()): Boolean {
        val ageMs = latestAgeMs(nowMs) ?: return false
        return ageMs <= MAX_FRESH_FRAME_AGE_MS
    }

    suspend fun captureFreshVisual(): VisualFrame? {
        freshStillProvider?.invoke()?.let { return it }
        val nowMs = System.currentTimeMillis()
        val jpeg = latestJpeg ?: return null
        val ageMs = latestAgeMs(nowMs) ?: return null
        if (ageMs > MAX_FRESH_FRAME_AGE_MS) return null
        return VisualFrame(
            base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP),
            capturedAtMs = latestCapturedAtMs,
            ageMs = ageMs,
            source = "latest_video_frame",
            width = latestWidth,
            height = latestHeight,
            jpegBytes = jpeg.size,
        )
    }

    fun bitmapToVisualFrame(bitmap: Bitmap, source: String, capturedAtMs: Long = System.currentTimeMillis()): VisualFrame {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, STILL_JPEG_QUALITY, output)
        val jpeg = output.toByteArray()
        return VisualFrame(
            base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP),
            capturedAtMs = capturedAtMs,
            ageMs = (System.currentTimeMillis() - capturedAtMs).coerceAtLeast(0L),
            source = source,
            width = bitmap.width,
            height = bitmap.height,
            jpegBytes = jpeg.size,
        )
    }
}
