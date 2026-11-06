package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Outside services the user links from Settings (Notion, Google Calendar, Slack).
 *
 * The server owns the sign-in: [linkUrl] returns a short-lived address the phone opens in the browser, the
 * service redirects back to the server, and the server stores the account. Settings re-reads [status] when the
 * user comes back to the app.
 */
object ServiceConnections {
    enum class Service(val key: String, val label: String, val icon: Int) {
        NOTION("notion", "노션", R.drawable.ic_service_notion),
        GOOGLE("google", "구글 캘린더", R.drawable.ic_service_google_calendar),
        SLACK("slack", "슬랙", R.drawable.ic_service_slack),
    }

    /** [ready] is false when the server has no sign-in app registered for this service yet. */
    data class State(val connected: Boolean, val ready: Boolean)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    private fun base() = GeminiConfig.jarvisApiBase.trimEnd('/')

    private fun request(path: String) = Request.Builder().url("${base()}$path")
        .addHeader("X-API-Key", GeminiConfig.jarvisApiKey)

    suspend fun status(): Map<Service, State> = withContext(Dispatchers.IO) {
        client.newCall(request("/integrations/status").get().build()).execute().use { response ->
            check(response.isSuccessful) { "서버 응답 ${response.code}" }
            val services = JSONObject(response.body?.string().orEmpty()).optJSONObject("services") ?: JSONObject()
            Service.entries.associateWith { service ->
                val item = services.optJSONObject(service.key) ?: JSONObject()
                State(item.optBoolean("connected"), item.optBoolean("ready", true))
            }
        }
    }

    suspend fun linkUrl(service: Service): String = withContext(Dispatchers.IO) {
        client.newCall(request("/integrations/${service.key}/link").get().build()).execute().use { response ->
            check(response.isSuccessful) { "서버 응답 ${response.code}" }
            JSONObject(response.body?.string().orEmpty()).getString("url")
        }
    }

    suspend fun disconnect(service: Service) = withContext(Dispatchers.IO) {
        client.newCall(request("/integrations/${service.key}").delete().build()).execute().use { response ->
            check(response.isSuccessful) { "서버 응답 ${response.code}" }
        }
    }
}
