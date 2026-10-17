package com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject

class PendingNetworkException : IOException("연결 복구 대기 중입니다. 같은 요청을 중복 저장하지 않습니다.")
private class SupersededRequest : CancellationException("취소되거나 변경된 요청입니다.")

/** Journal IO is short-lived; unrelated requests never hold a global network lock. */
class DurableRequests(private val directory: File, private val client: OkHttpClient) {
    private val journal = Mutex()
    private val monitor = Any()
    private val locks = mutableMapOf<String, Mutex>()
    private class Flight(var cancelled: Boolean = false, var call: Call? = null)
    private val flights = mutableMapOf<String, Flight>()
    private val capabilities = mutableMapOf<String, Long>()
    private val retryAfter = mutableMapOf<String, Pair<Int, Long>>()
    private fun lock(key: String) = synchronized(monitor) { locks.getOrPut(key) { Mutex() } }
    private fun identity(base: String, apiKey: String) = MessageDigest.getInstance("SHA-256")
        .digest("$base\n$apiKey".toByteArray()).joinToString("") { "%02x".format(it) }
    private fun file(key: String): File {
        require(key.matches(Regex("[a-zA-Z0-9:-]{1,100}")))
        return File(directory, "$key.json")
    }
    /** Also cancels the capability check, before a mutation has been sent. */
    fun cancel(key: String) = synchronized(monitor) {
        flights[key]?.let { it.cancelled = true; it.call?.cancel() }
    }
    internal suspend fun send(key: String, base: String, apiKey: String, path: String, body: String): TaskHttpResponse = lock(key).withLock {
        val data = journal.withLock {
            val target = file(key)
            if (!target.exists()) {
                directory.mkdirs()
                check(directory.listFiles().orEmpty().sumOf { it.length() } + body.length * 3L < 64_000_000) { "오프라인 보관함이 가득 찼습니다." }
                val data = JSONObject().put("key", key).put("base", base).put("identity", identity(base, apiKey)).put("path", path).put("body", body)
                val temp = File(directory, "$key.tmp")
                temp.outputStream().use { it.write(data.toString().toByteArray()); it.fd.sync() }
                check(temp.renameTo(target))
            }
            JSONObject(target.readText()).also {
                check(it.getString("identity") == identity(base, apiKey)) { "저장 요청의 서버 또는 계정이 바뀌었습니다." }
                check(it.getString("path") == path && it.getString("body") == body) { "재시도 요청 내용이 변경되었습니다." }
            }
        }
        transmit(data, apiKey) { true }
    }
    private suspend fun transmit(data: JSONObject, apiKey: String, eligible: () -> Boolean): TaskHttpResponse {
        val key = data.getString("key")
        val flight = Flight()
        synchronized(monitor) { flights[key] = flight }
        suspend fun request(request: Request): TaskHttpResponse {
            val call = synchronized(monitor) {
                if (flight.cancelled || !eligible()) throw SupersededRequest()
                client.newCall(request).also { flight.call = it }
            }
            return call.awaitTaskResponse()
        }
        try {
            val identity = data.getString("identity")
            val supported = synchronized(monitor) { capabilities[identity]?.let { System.nanoTime() - it < 300_000_000_000L } ?: false }
            if (!supported) {
                val capability = request(Request.Builder().url(data.getString("base") + "/assistant/capabilities").header("X-API-Key", apiKey).build())
                if (capability.code >= 500 || capability.code in setOf(408, 429)) throw PendingNetworkException()
                check(capability.code == 200 && JSONObject(capability.body).optInt("atomic_idempotency") == 1) { "서버를 업데이트한 뒤 저장해주세요." }
                synchronized(monitor) { capabilities[identity] = System.nanoTime() }
            }
            // Recheck revision AFTER capability IO. The registered Call is cancellable until completion.
            val response = request(Request.Builder().url(data.getString("base") + data.getString("path"))
                .header("X-API-Key", apiKey).header("Idempotency-Key", key)
                .post(data.getString("body").toRequestBody("application/json".toMediaType())).build())
            if (response.code >= 500 || response.code in setOf(408, 429)) throw PendingNetworkException()
            return response
        } catch (e: IOException) {
            if (synchronized(monitor) { flight.cancelled } || !eligible()) throw SupersededRequest()
            throw PendingNetworkException()
        } finally { synchronized(monitor) { flights.remove(key) } }
    }
    suspend fun recover(base: String, apiKey: String, eligible: (String) -> Boolean, complete: suspend (String, Int, String) -> Unit) {
        val files = journal.withLock { directory.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.lastModified() } }
        for (target in files) {
            val data = journal.withLock { if (target.exists()) JSONObject(target.readText()) else null } ?: continue
            val key = data.getString("key")
            if (data.getString("identity") != identity(base, apiKey) || !eligible(key)) continue
            val next = synchronized(monitor) { retryAfter[key]?.second }
            if (next != null && System.nanoTime() - next < 0L) continue
            lock(key).withLock {
                if (!eligible(key)) return@withLock
                try {
                    val response = transmit(data, apiKey) { eligible(key) }
                    complete(key, response.code, response.body) // Caller durably persists before journal removal.
                    journal.withLock { target.delete() }
                    synchronized(monitor) { retryAfter.remove(key) }
                } catch (_: SupersededRequest) {
                    // The superseded journal is removed on the next maintenance pass.
                } catch (_: PendingNetworkException) {
                    synchronized(monitor) {
                        val attempt = minOf((retryAfter[key]?.first ?: 0) + 1, 4)
                        retryAfter[key] = attempt to (System.nanoTime() + (5_000L shl attempt) * 1_000_000L)
                    }
                    // Continue: a failed item must not starve the rest of the queue.
                }
            }
        }
    }
    suspend fun discardSuperseded(currentRevision: (String) -> Int?) = journal.withLock {
        directory.listFiles().orEmpty().filter { it.extension == "json" }.forEach { target ->
            val key = JSONObject(target.readText()).getString("key")
            if (currentRevision(key.substringBefore(':'))?.toString() != key.substringAfter(':')) {
                cancel(key)
                target.delete()
                synchronized(monitor) { retryAfter.remove(key) }
            }
        }
    }
    suspend fun acknowledge(key: String) = journal.withLock { file(key).delete(); Unit }
}
