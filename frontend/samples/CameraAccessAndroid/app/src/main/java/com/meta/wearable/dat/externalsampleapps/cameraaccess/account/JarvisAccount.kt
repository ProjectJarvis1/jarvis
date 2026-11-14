package com.meta.wearable.dat.externalsampleapps.cameraaccess.account

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.Secrets
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The signed-in Jarvis user. The app signs in with Google, the server answers with its own session
 * token, and every server call sends it as `Authorization: Bearer`. No server or Gemini key ships
 * in the app any more; the server keeps each user's records apart.
 */
object JarvisAccount {
    data class User(val email: String, val name: String)

    private const val PREFS = "jarvis_account"
    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // A deleted account's folder is erased right away; erase it again here in case work that was still
        // running then (the conversation screen's task store, the outbox) wrote into it afterwards.
        eraseDeletedFolders(context.applicationContext)
        _user.value = prefs.getString("token", null)?.let {
            User(prefs.getString("email", "").orEmpty(), prefs.getString("name", "").orEmpty())
        }
    }

    /** Server user id; local task history, results and the outbox live under [dataDir] per user. */
    val userId: String get() = if (::prefs.isInitialized) prefs.getString("uid", null).orEmpty() else ""

    /** This user's folder for on-device records, so another account on the same phone never sees them. */
    fun dataDir(context: Context): java.io.File =
        java.io.File(context.filesDir, "users/" + userId.ifBlank { "signed-out" }.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    /**
     * Added to every OkHttp client that talks to the Jarvis server: a 401 for the session it sent means
     * the session is gone (expired, signed out elsewhere, account deleted), so the app returns to sign-in.
     */
    val sessionGuard = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val sent = request.header("Authorization")
        if (response.code == 401 && sent != null && sent.isNotEmpty() && sent == authorization) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (authorization == sent) onUnauthorized() }
        }
        response
    }

    /** Checks a session restored from storage once at start-up; an offline phone keeps it. */
    suspend fun validate() {
        if (!isSignedIn) return
        val sent = authorization
        val code = runCatching {
            withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(base() + "/auth/me").header("Authorization", sent).build()).execute().use { it.code }
            }
        }.getOrNull()
        if (code == 401 && authorization == sent) clearLocal()
    }

    val isSignedIn: Boolean get() = ::prefs.isInitialized && prefs.getString("token", null) != null

    /** Header value for Jarvis server calls; empty when signed out, which the server answers with 401. */
    val authorization: String
        get() = if (::prefs.isInitialized) prefs.getString("token", null)?.let { "Bearer $it" }.orEmpty() else ""

    /** Emulators without Google Play services sign in by email; the server allows it only when ALLOW_DEV_LOGIN is on. */
    val devSignInAvailable: Boolean get() = BuildConfig.DEBUG

    suspend fun signInWithGoogle(activity: Activity): Result<User> = runCatching {
        val clientId = Secrets.googleWebClientId
        require(clientId.isNotBlank()) { "구글 로그인 설정(googleWebClientId)이 비어 있습니다." }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "구글 계정 정보를 받지 못했습니다."
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        exchange("/auth/google", JSONObject().put("id_token", idToken))
    }

    suspend fun signInForDevelopment(email: String): Result<User> = runCatching {
        check(BuildConfig.DEBUG)
        exchange("/auth/dev", JSONObject().put("email", email.trim()))
    }

    private suspend fun exchange(path: String, body: JSONObject): User = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base() + path)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) { serverMessage(text) ?: "로그인하지 못했습니다(${response.code})." }
            val json = JSONObject(text)
            val user = json.getJSONObject("user")
            prefs.edit().putString("token", json.getString("token")).putString("uid", user.getString("id"))
                .putString("email", user.optString("email")).putString("name", user.optString("name")).apply()
            appContext?.let { keepFolder(dataDir(it)) }
            User(user.optString("email"), user.optString("name")).also { _user.value = it }
        }
    }

    /** Ends the session on the server too; local sign-out happens even when offline. */
    suspend fun signOut() {
        val auth = authorization
        runCatching {
            withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(base() + "/auth/logout").header("Authorization", auth)
                    .post(ByteArray(0).toRequestBody()).build()).execute().close()
            }
        }
        clearLocal()
    }

    /** Deletes the account and every record on the server. Fails without signing out, so the user can retry. */
    suspend fun deleteAccount(): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(base() + "/auth/me").header("Authorization", authorization).delete().build())
                .execute().use { response ->
                    check(response.isSuccessful) { serverMessage(response.body?.string().orEmpty()) ?: "계정을 지우지 못했습니다(${response.code})." }
                }
        }
        // Unlike signing out, deleting the account also removes this phone's copies of its records: task
        // history and results (photos, search answers) and requests waiting to be resent.
        val folder = appContext?.let { dataDir(it) }
        clearLocal()
        folder?.let { markForErase(it); eraseDeletedFolders(appContext!!) }
    }

    private const val ERASE_PREFS = "jarvis_erase"

    /**
     * Entries are "pid|folder". Work of the deleted account that was still running in this process (the
     * conversation screen's task store, the outbox) may write into the folder after it is erased, so an entry
     * is dropped only by a later process, after erasing the folder once more; this process only erases.
     */
    private fun markForErase(folder: java.io.File) {
        val prefs = appContext?.getSharedPreferences(ERASE_PREFS, Context.MODE_PRIVATE) ?: return
        val pending = prefs.getStringSet("folders", emptySet()).orEmpty() + "${android.os.Process.myPid()}|${folder.absolutePath}"
        prefs.edit().putStringSet("folders", pending).commit()
    }

    /** A signed-in account's own folder is never erased later, even if an earlier deletion listed it. */
    private fun keepFolder(folder: java.io.File) {
        val prefs = appContext?.getSharedPreferences(ERASE_PREFS, Context.MODE_PRIVATE) ?: return
        val pending = prefs.getStringSet("folders", emptySet()).orEmpty()
        val left = pending.filterNot { it.substringAfter('|') == folder.absolutePath }.toSet()
        if (left.size != pending.size) prefs.edit().putStringSet("folders", left).commit()
    }

    /** Erases folders of deleted accounts; see [markForErase] for when an entry is let go. */
    private fun eraseDeletedFolders(context: Context) {
        val prefs = context.getSharedPreferences(ERASE_PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getStringSet("folders", emptySet()).orEmpty()
        if (pending.isEmpty()) return
        val usersRoot = java.io.File(context.filesDir, "users").canonicalFile
        val me = android.os.Process.myPid().toString()
        val left = pending.filterNot { entry ->
            val dir = java.io.File(entry.substringAfter('|')).canonicalFile
            // Only a folder directly under files/users is ever removed.
            if (dir.parentFile != usersRoot) return@filterNot true
            dir.deleteRecursively() && entry.substringBefore('|') != me
        }.toSet()
        prefs.edit().putStringSet("folders", left).commit()
    }

    /** The server no longer accepts the session (expired, deleted elsewhere). */
    fun onUnauthorized() = clearLocal()

    private fun clearLocal() {
        com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ExternalTools.clear()
        // Offers Jarvis made for this account, and which events it already offered, go with the account. The
        // store's lock is held until the session is gone, so a background pass cannot record or post in between.
        val store = com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.ProactiveStore
        synchronized(store) {
            appContext?.let { store.clear(it) }
            prefs.edit().clear().apply()
        }
        _user.value = null
        // After the session is gone, so a briefing finishing now is no longer posted for this account.
        appContext?.let { com.meta.wearable.dat.externalsampleapps.cameraaccess.notify.JarvisNotifications.clearAccount(it) }
    }

    private fun base() = SettingsManager.jarvisApiBase.trimEnd('/')

    private fun serverMessage(text: String): String? =
        runCatching { JSONObject(text).optString("detail").takeIf { it.isNotBlank() } }.getOrNull()
}
