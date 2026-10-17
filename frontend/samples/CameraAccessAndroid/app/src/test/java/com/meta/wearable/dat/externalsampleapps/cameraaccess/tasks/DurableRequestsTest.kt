package com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class DurableRequestsTest {
    @Test fun lostResponsePersistsExactBodyAndRestartUsesSameKey() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        val keys = mutableListOf<String?>()
        val bodies = mutableListOf<String>()
        var online = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (!online) throw IOException("offline")
            if (request.url.encodedPath.endsWith("capabilities")) {
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                    .body("{\"atomic_idempotency\":1}".toResponseBody()).build()
            } else {
                keys.add(request.header("Idempotency-Key"))
                val buffer = okio.Buffer(); request.body!!.writeTo(buffer); bodies.add(buffer.readUtf8())
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                    .body("{\"id\":\"record\"}".toResponseBody()).build()
            }
        }.build()
        try {
            val queue = DurableRequests(directory, client)
            try { queue.send("task:1", "http://localhost", "secret-key", "/memory/save", "{\"text\":\"original\"}"); fail() }
            catch (_: PendingNetworkException) { }
            assertTrue(File(directory, "task:1.json").exists())
            assertFalse(File(directory, "task:1.json").readText().contains("secret-key"))
            online = true
            val restored = DurableRequests(directory, client)
            var completed = false
            restored.recover("http://localhost", "other-account", { true }) { _, _, _ -> fail("wrong identity") }
            restored.recover("http://localhost", "secret-key", { false }) { _, _, _ -> fail("cancelled revision") }
            assertTrue(keys.isEmpty())
            restored.recover("http://localhost", "secret-key", { it == "task:1" }) { key, code, _ ->
                assertEquals("task:1", key); assertEquals(200, code); completed = true
            }
            assertTrue(completed)
            assertEquals(listOf("task:1"), keys)
            assertEquals(listOf("{\"text\":\"original\"}"), bodies)
            assertFalse(File(directory, "task:1.json").exists())
            online = false
            try { queue.send("cancelled:1", "http://localhost", "secret-key", "/memory/save", "{}"); fail() }
            catch (_: PendingNetworkException) { }
            assertTrue(File(directory, "cancelled:1.json").exists())
            restored.discardSuperseded { 2 }
            assertFalse(File(directory, "cancelled:1.json").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun oldServerIsNotAllowedToExecuteRetryableWrites() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        var writes = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().method == "POST") writes++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("old server")
                .body("{}".toResponseBody()).build()
        }.build()
        try {
            try { DurableRequests(directory, client).send("task:1", "http://localhost", "key", "/memory/save", "{}"); fail() }
            catch (_: IllegalStateException) { }
            assertEquals(0, writes)
        } finally { directory.deleteRecursively() }
    }
}
