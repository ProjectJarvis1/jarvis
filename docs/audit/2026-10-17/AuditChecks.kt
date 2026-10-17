package com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject

// Current-source regression checks for the defects found during the audit.
fun main() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val store = object : TaskStore {
        override suspend fun load() = emptyList<TaskRecord>()
        override suspend fun save(tasks: List<TaskRecord>) = Unit
    }
    try {
        val manager = TaskManager(scope, store, { _, _ -> TaskOutcome(result = "{}") }, maxRecords = 2)
        manager.initialize()
        val source = manager.submit("search_memory", "{}", "source")
        check(manager.get(source.id)!!.state == TaskState.COMPLETED)
        manager.submit("search_memory", "{}", "other")
        val child = manager.submit("search_memory", "{}", "dependent", source.id, source.revision)
        check(manager.get(source.id) != null)
        check(manager.get(child.id)!!.state == TaskState.COMPLETED)
        println("FIXED: validated source survives history pruning")

        val big = JSONObject().put("text", "x".repeat(65_000)).toString()
        val truncation = TaskManager(scope, store, { _, _ -> TaskOutcome(result = big) })
        truncation.initialize()
        val task = truncation.submit("search_memory", "{}", "large JSON")
        val result = truncation.get(task.id)!!
        check(result.state == TaskState.COMPLETED && result.resultTruncated)
        check(runCatching { JSONObject(result.result!!) }.isSuccess)
        println("FIXED: large result keeps a valid JSON preview")

        val offline = AtomicBoolean(true)
        val eligible = AtomicBoolean(true)
        val posts = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (offline.get()) throw java.io.IOException("simulated offline")
            val body = if (request.method == "GET") {
                // Cancellation/revision change while capability request is in flight.
                eligible.set(false)
                "{\"atomic_idempotency\":1}"
            } else {
                check(!eligible.get())
                posts.incrementAndGet()
                "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(body.toResponseBody()).build()
        }.build()
        val directory = Files.createTempDirectory("jarvis-outbox-audit").toFile()
        try {
            val outbox = DurableRequests(directory, client)
            try { outbox.send("test:1", "https://example.invalid", "dummy", "/memory/save", "{}") }
            catch (_: PendingNetworkException) { }
            offline.set(false)
            outbox.recover("https://example.invalid", "dummy", { eligible.get() }, { _, _, _ -> })
            check(posts.get() == 0)
            println("FIXED: superseded recovery never sends POST after capability GET")
        } finally {
            directory.deleteRecursively()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    } finally { scope.cancel() }
}
