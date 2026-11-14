package com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting

import java.io.File
import org.json.JSONObject

/**
 * Recordings kept for later (refused by the day's limit) and sent again oldest first.
 * A temporary refusal (limit, network, server error) stops the run and keeps the file for the next try.
 * A permanent refusal (for example no speech in it) moves the file to [failedDir] and the run goes on,
 * so one bad recording never blocks the ones after it. Both folders keep files a week at most.
 */
class PendingRecordingQueue(
    private val pendingDir: File,
    private val failedDir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Item(val file: File, val title: String, val startedAt: String, val endedAt: String)

    companion object {
        const val KEEP_MS = 7 * 24 * 3_600_000L

        /** 4xx answers that will repeat for the same file. 401/403 (sign-in), 408 and 429 (limit) are temporary. */
        fun isPermanentFailure(code: Int): Boolean = code in 400..499 && code !in setOf(401, 403, 408, 409, 425, 429)
    }

    fun keep(file: File, title: String, startedAt: String, endedAt: String): Boolean = runCatching {
        pendingDir.mkdirs()
        val kept = File(pendingDir, file.name)
        if (!file.renameTo(kept)) { file.copyTo(kept, overwrite = true); file.delete() }
        File(pendingDir, "${file.name}.json").writeText(
            JSONObject().put("title", title).put("started_at", startedAt).put("ended_at", endedAt).toString())
        true
    }.getOrDefault(false)

    /**
     * Sends what is waiting. [shouldStop] is asked before each file (a new recording has started).
     * Returns normally after a temporary refusal; the file stays for the next run.
     */
    suspend fun drain(
        upload: suspend (Item) -> MeetingRecordingResult,
        onResult: suspend (MeetingRecordingResult) -> Unit,
        onPermanentFailure: suspend (Item, PermanentUploadFailure) -> Unit,
        shouldStop: () -> Boolean = { false },
    ) {
        purgeOld(failedDir)
        val files = pendingDir.listFiles { f -> f.name.endsWith(".m4a") }?.sortedBy { it.name }.orEmpty()
        for (file in files) {
            if (shouldStop()) return
            val meta = File(file.parentFile, "${file.name}.json")
            if (now() - file.lastModified() > KEEP_MS) { file.delete(); meta.delete(); continue }
            val info = runCatching { JSONObject(meta.readText()) }.getOrNull()
            if (info == null) { file.delete(); meta.delete(); continue }
            val item = Item(file, info.optString("title"), info.optString("started_at"), info.optString("ended_at"))
            val result = try {
                upload(item)
            } catch (e: PermanentUploadFailure) {
                moveToFailed(file, meta)
                onPermanentFailure(item, e)
                continue
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
            file.delete(); meta.delete()
            onResult(result)
        }
    }

    private fun moveToFailed(file: File, meta: File) {
        runCatching {
            failedDir.mkdirs()
            for (f in listOf(file, meta)) {
                if (!f.exists()) continue
                val target = File(failedDir, f.name)
                if (!f.renameTo(target)) { f.copyTo(target, overwrite = true); f.delete() }
                target.setLastModified(now())
            }
        }.onFailure { file.delete(); meta.delete() }
    }

    private fun purgeOld(dir: File) {
        dir.listFiles()?.forEach { if (now() - it.lastModified() > KEEP_MS) it.delete() }
    }
}
