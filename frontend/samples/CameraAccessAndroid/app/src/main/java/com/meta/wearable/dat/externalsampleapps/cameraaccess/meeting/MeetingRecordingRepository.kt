package com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting

import com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount

import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConfig
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

class MeetingRecordingRepository {
    private val client = OkHttpClient.Builder().addInterceptor(JarvisAccount.sessionGuard)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    fun upload(
        file: File,
        title: String,
        startedAt: String,
        endedAt: String,
    ): MeetingRecordingResult {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio",
                file.name,
                file.asRequestBody("audio/mp4".toMediaType()),
            )
            .addFormDataPart("title", title)
            .addFormDataPart("started_at", startedAt)
            .addFormDataPart("ended_at", endedAt)
            .addFormDataPart("person_ids", "[]")
            .build()
        val request = Request.Builder()
            .url("${GeminiConfig.jarvisApiBase.trimEnd('/')}/meetings/recordings")
            .addHeader("Authorization", GeminiConfig.jarvisAuthorization)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (response.code == 429) {
                // Today's limit: the server says so in plain Korean ("오늘 정리할 수 있는 회의 녹음 수를 모두 썼어요...").
                throw QuotaExceeded(runCatching { JSONObject(responseBody).getString("detail") }
                    .getOrDefault("오늘 쓸 수 있는 양을 모두 썼어요."))
            }
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(responseBody).getString("detail") }.getOrDefault(responseBody.take(300))
                // The same file will fail the same way (no speech, bad format, too large): do not retry it forever.
                if (PendingRecordingQueue.isPermanentFailure(response.code)) throw PermanentUploadFailure(response.code, detail)
                throw IllegalStateException("HTTP ${response.code}: ${responseBody.take(300)}")
            }
            val json = JSONObject(responseBody)
            val summary = json.getJSONObject("summary")
            return MeetingRecordingResult(
                meetingId = json.getJSONObject("meeting").getString("id"),
                memoryId = json.getString("memory_id"),
                title = summary.optString("title", title),
                summary = summary.optString("summary"),
                decisions = summary.optJSONArray("decisions")?.toStringList().orEmpty(),
                actionItems = summary.optJSONArray("action_items")?.toStringList().orEmpty(),
                followups = MeetingFollowups.parse(
                    summary.optJSONArray("followups"),
                    summary.optJSONArray("action_items")?.toStringList().orEmpty(),
                ),
                startedAt = startedAt,
            )
        }
    }

    private fun org.json.JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
}

data class MeetingRecordingResult(
    val meetingId: String,
    val memoryId: String,
    val title: String,
    val summary: String,
    val decisions: List<String>,
    val actionItems: List<String>,
    val followups: List<MeetingFollowup> = actionItems.map { MeetingFollowup(it) },
    val startedAt: String = "",
)


/** The server refused the upload because today's meeting limit is used up; the recording is kept for later. */
class QuotaExceeded(message: String) : Exception(message)

/** The server refused this recording for good (for example 422: no speech was transcribed). */
class PermanentUploadFailure(val code: Int, message: String) : Exception(message)
