package com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StabilityRegressionTest {
    private class Store(var data: List<TaskRecord> = emptyList()) : TaskStore {
        var archived: String? = null
        override suspend fun load() = data
        override suspend fun save(tasks: List<TaskRecord>) { data = tasks }
        override suspend fun archiveResult(key: String, result: String): String { archived = result; return "$key.json" }
    }
    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try { scope.block() } finally { scope.cancel() }
    }
    @Test fun submissionProtectsItsCompletedSourceAtCapacity() = test {
        val manager = TaskManager(this, Store(), { _, _ -> TaskOutcome(result = "{}") }, maxRecords = 2)
        manager.initialize()
        val source = manager.submit("search_memory", "{}", "source")
        val other = manager.submit("search_memory", "{}", "other")
        val child = manager.submit("search_memory", "{}", "child", source.id, 1)
        assertNotNull(manager.get(source.id))
        assertNull(manager.get(other.id))
        assertEquals(TaskState.COMPLETED, manager.get(child.id)!!.state)
    }
    @Test fun pendingDependentProtectsSourceAcrossRestartAndExpiry() = test {
        val source = TaskRecord("source", tool = "search_memory", argsJson = "{}", title = "source", createdAt = 0, updatedAt = 0, state = TaskState.COMPLETED)
        val child = TaskRecord("child", tool = "save_memory", argsJson = "{}", title = "child", createdAt = 1, updatedAt = 1, state = TaskState.WAITING_USER, sourceTaskId = "source", sourceRevision = 1)
        val manager = TaskManager(this, Store(listOf(source, child)), { _, _ -> TaskOutcome() }, now = { 1000 }, retentionMs = 10)
        manager.initialize()
        assertNotNull(manager.get("source"))
    }
    @Test fun largeResultRetainsFullArchiveAndValidStructuredPreview() = test {
        val store = Store()
        val raw = JSONObject().put("text", "x".repeat(65_000)).put("id", "memory-id").toString()
        val manager = TaskManager(this, store, { _, _ -> TaskOutcome(result = raw) })
        manager.initialize()
        val task = manager.submit("search_memory", "{}", "large")
        val saved = manager.get(task.id)!!
        assertEquals(raw, store.archived)
        assertEquals("memory-id", JSONObject(saved.result!!).getString("id"))
        assertTrue(saved.resultTruncated)
        assertNotNull(saved.resultFile)
    }
    @Test fun recoveredLargeResultAlsoMarksPreviewAndArchives() = test {
        val old = TaskRecord("task", tool = "save_memory", argsJson = "{}", title = "save", createdAt = 0, updatedAt = 0,
            dispatched = true, state = TaskState.WAITING_NETWORK)
        val store = Store(listOf(old))
        val manager = TaskManager(this, store, { _, _ -> error("must replay, not execute") })
        manager.initialize()
        val body = JSONObject().put("text", "x".repeat(70_000)).toString()
        manager.recover("task:1", 200, body)
        assertEquals(body, store.archived)
        val saved = manager.get("task")!!
        assertTrue(saved.resultTruncated)
        assertNotNull(JSONObject(saved.result!!))
    }
    @Test fun staleSearchCompletingAfterCorrectionCannotResurrectReference() {
        val search = TaskRecord("search", tool = "search_memory", argsJson = "{}", title = "search", createdAt = 1, updatedAt = 5,
            state = TaskState.COMPLETED, result = "{\"id\":\"memory\",\"captured_at\":\"today\",\"text\":\"old\"}")
        val edit = TaskRecord("edit", tool = "apply_record_change", argsJson = "{}", title = "edit", createdAt = 2, updatedAt = 3,
            state = TaskState.COMPLETED, result = "{\"record_id\":\"memory\"}")
        assertTrue(ConversationReferences.from(listOf(search, edit)).isEmpty())
    }
    private fun response(request: Request, body: String = "{}") = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(200).message("ok").body(body.toResponseBody()).build()
    @Test fun cancelledDuringCapabilityCheckNeverSendsMutation() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        val online = AtomicBoolean(false)
        val eligible = AtomicBoolean(true)
        var writes = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (!online.get()) throw IOException("offline")
            if (chain.request().method == "GET") {
                eligible.set(false)
                response(chain.request(), "{\"atomic_idempotency\":1}")
            } else { writes++; response(chain.request()) }
        }.build()
        try {
            val outbox = DurableRequests(directory, client)
            try { outbox.send("task:1", "https://example.invalid", "dummy", "/memory/save", "{}") } catch (_: PendingNetworkException) { }
            online.set(true)
            outbox.recover("https://example.invalid", "dummy", { eligible.get() }) { _, _, _ -> fail("superseded") }
            assertEquals(0, writes)
        } finally { directory.deleteRecursively(); client.dispatcher.executorService.shutdownNow() }
    }
    @Test fun slowRequestDoesNotBlockUnrelatedJournalOrNetwork() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") response(request, "{\"atomic_idempotency\":1}")
            else {
                if (request.header("Idempotency-Key") == "slow:1") {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
                response(request)
            }
        }.build()
        try {
            val outbox = DurableRequests(directory, client)
            val slow = async(Dispatchers.IO) { outbox.send("slow:1", "https://example.invalid", "dummy", "/memory/save", "{}") }
            withContext(Dispatchers.IO) { check(entered.await(3, TimeUnit.SECONDS)) }
            val fast = withTimeout(1500) { outbox.send("fast:1", "https://example.invalid", "dummy", "/memory/save", "{}") }
            assertEquals(200, fast.code)
            release.countDown(); slow.await(); Unit
        } finally { release.countDown(); directory.deleteRecursively(); client.dispatcher.executorService.shutdownNow() }
    }
    @Test fun photoOrdinalSurvivesLaterInterpretationUpdate() {
        val first = TaskRecord("first", tool = "capture_current_view", argsJson = "{}", title = "first", createdAt = 1, updatedAt = 10, state = TaskState.COMPLETED)
        val second = first.copy(id = "second", title = "second", createdAt = 2, updatedAt = 2)
        val selected = ConversationReferences.resolve(listOf(first, second), "photo", ordinal = 1)
        assertEquals("first", selected.getJSONArray("candidates").getJSONObject(0).getString("record_id"))
    }
    @Test fun uncertainFaceCandidateDoesNotBecomeAResolvedIdentity() {
        val face = TaskRecord("face", tool = "identify_person", argsJson = "{}", title = "face", createdAt = 1, updatedAt = 2, state = TaskState.COMPLETED,
            result = "{\"decision\":\"uncertain\",\"matches\":[{\"person\":{\"id\":\"p1\",\"name\":\"candidate\"}}]}")
        assertTrue(ConversationReferences.from(listOf(face)).isEmpty())
    }

}
