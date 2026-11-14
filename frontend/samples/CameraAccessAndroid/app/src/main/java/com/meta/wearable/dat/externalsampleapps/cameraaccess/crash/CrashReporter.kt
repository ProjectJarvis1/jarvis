package com.meta.wearable.dat.externalsampleapps.cameraaccess.crash

import android.content.Context
import android.os.Build
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConfig
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * When the app crashes, keeps a small report on the phone and sends it to Jarvis's own server on the
 * next start. No outside crash service. A report holds the exception classes and code lines, the app
 * version and build (glasses or phone), the phone model and Android version. Exception messages are
 * left out on purpose: they can carry a name, a memo or a sentence the user said.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val DIR = "crash-reports"
    private const val KEEP_FILES = 5
    private const val KEEP_MS = 7 * 24 * 3_600_000L
    const val MAX_STACK = 16_000

    @Volatile private var installed = false
    private val client by lazy {
        OkHttpClient.Builder().addInterceptor(JarvisAccount.sessionGuard).connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).build()
    }

    /** Wraps the existing crash handler; the app still closes as before. Safe to call more than once. */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(app, error, System.currentTimeMillis()) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Exception classes and code lines only, causes included, at most [MAX_STACK] characters. */
    fun stackOf(error: Throwable): String {
        val out = StringBuilder()
        var t: Throwable? = error
        var depth = 0
        val seen = HashSet<Throwable>()
        while (t != null && depth < 8 && seen.add(t)) {
            out.append(if (depth == 0) "" else "Caused by: ").append(t.javaClass.name).append('\n')
            t.stackTrace.take(40).forEach { out.append("\tat ").append(it.toString()).append('\n') }
            t = t.cause
            depth++
        }
        return out.toString().take(MAX_STACK)
    }

    /** The JSON a report is kept and sent as. */
    fun report(error: Throwable, nowMs: Long): JSONObject = JSONObject()
        .put("stack", stackOf(error))
        .put("happened_at", Instant.ofEpochMilli(nowMs).toString())
        .put("app_version", BuildConfig.VERSION_NAME)
        .put("flavor", BuildConfig.FLAVOR)
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(120))
        .put("os_version", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

    /** Signed in: the account's own folder, which sign-out and account deletion erase. Signed out: a shared folder. */
    private fun dir(context: Context, uid: String) =
        if (uid.isBlank()) File(context.filesDir, DIR) else File(JarvisAccount.dataDir(context), DIR)

    private fun save(context: Context, error: Throwable, nowMs: Long) {
        val folder = dir(context, JarvisAccount.userId).apply { mkdirs() }
        File(folder, "$nowMs.json").writeText(report(error, nowMs).toString())
        folder.listFiles()?.sortedByDescending { it.name }?.drop(KEEP_FILES)?.forEach { it.delete() }
    }

    /**
     * Sends the kept reports of the signed-in account. A report is dropped once the server took it or
     * refused it for good (4xx other than 401/429); a network error keeps it for the next start. Reports
     * are kept in each account's own folder; one made while signed out goes with the next account. Any
     * report older than a week is dropped.
     */
    fun uploadPending(context: Context, nowMs: Long = System.currentTimeMillis()) {
        if (!JarvisAccount.isSignedIn || !GeminiConfig.isJarvisConfigured) return
        // The account and its session are taken once: a switch while sending stops the run, so one account's
        // reports never go out under another's session.
        val uid = JarvisAccount.userId
        val auth = GeminiConfig.jarvisAuthorization
        val files = (listOf(dir(context, uid), dir(context, "")).flatMap { it.listFiles()?.toList().orEmpty() })
            .sortedBy { it.name }
        for (file in files) {
            if (!JarvisAccount.isSignedIn || JarvisAccount.userId != uid || GeminiConfig.jarvisAuthorization != auth) return
            val json = runCatching { JSONObject(file.readText()) }.getOrNull()
            val age = nowMs - (file.name.removeSuffix(".json").toLongOrNull() ?: 0L)
            if (json == null || age > KEEP_MS) { file.delete(); continue }
            val code = runCatching {
                client.newCall(Request.Builder()
                    .url("${GeminiConfig.jarvisApiBase.trimEnd('/')}/client-errors")
                    .addHeader("Authorization", auth)
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()).execute().use { it.code }
            }.onFailure { Log.w(TAG, "Crash report not sent: ${it.javaClass.simpleName}") }.getOrNull() ?: return
            if (shouldDrop(code)) file.delete() else if (code == 401) return
        }
    }

    /** True when a report is done with: taken (2xx) or refused for good (4xx other than 401 and 429). */
    fun shouldDrop(code: Int) = code in 200..299 || (code in 400..499 && code != 401 && code != 429)
}
