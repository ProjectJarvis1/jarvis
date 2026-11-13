package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount

import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Outside services the user links from Settings (Notion, Google Calendar, Gmail, Slack).
 *
 * The server owns the sign-in: [linkUrl] returns a short-lived address the phone opens in the browser, the
 * service redirects back to the server, and the server stores the account. Settings re-reads [status] when the
 * user comes back to the app.
 */
object ServiceConnections {
    enum class Service(val key: String, val label: String, val icon: Int) {
        NOTION("notion", "노션", R.drawable.ic_service_notion),
        GOOGLE("google", "구글 캘린더", R.drawable.ic_service_google_calendar),
        // Same Google sign-in with mail read access added; the server asks only for what this row needs.
        GMAIL("gmail", "지메일", R.drawable.ic_service_gmail),
        SLACK("slack", "슬랙", R.drawable.ic_service_slack),
    }

    /** [ready] is false when the server has no sign-in app registered for this service yet. */
    data class State(val connected: Boolean, val ready: Boolean)

    private val client = OkHttpClient.Builder().addInterceptor(JarvisAccount.sessionGuard)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    private fun base() = GeminiConfig.jarvisApiBase.trimEnd('/')

    private fun request(path: String) = Request.Builder().url("${base()}$path")
        .addHeader("Authorization", GeminiConfig.jarvisAuthorization)

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

    class ServiceRefused(message: String) : Exception(message)

    suspend fun linkUrl(service: Service): String = withContext(Dispatchers.IO) {
        client.newCall(request("/integrations/${service.key}/link").get().build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            // A 400 explains itself (e.g. Notion is admin-only for now); show that sentence to the user.
            if (response.code == 400) throw ServiceRefused(runCatching { JSONObject(text).getString("detail") }.getOrDefault("연결할 수 없어요."))
            check(response.isSuccessful) { "서버 응답 ${response.code}" }
            JSONObject(text).getString("url")
        }
    }

    suspend fun disconnect(service: Service) = withContext(Dispatchers.IO) {
        client.newCall(request("/integrations/${service.key}").delete().build()).execute().use { response ->
            check(response.isSuccessful) { "서버 응답 ${response.code}" }
        }
    }
}
