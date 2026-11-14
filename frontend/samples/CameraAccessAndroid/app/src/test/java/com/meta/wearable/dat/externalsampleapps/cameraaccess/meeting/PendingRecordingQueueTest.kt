package com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRecordingQueueTest {
    private fun setup(): Triple<File, File, PendingRecordingQueue> {
        val root = Files.createTempDirectory("pending").toFile()
        val pending = File(root, "pending-recordings")
        val failed = File(root, "failed-recordings")
        return Triple(pending, failed, PendingRecordingQueue(pending, failed))
    }

    private fun PendingRecordingQueue.add(dir: File, name: String) {
        val src = File(dir.parentFile, name).apply { writeText("audio $name") }
        assertTrue(keep(src, "회의 $name", "2026-11-14T10:00:00+09:00", "2026-11-14T11:00:00+09:00"))
    }

    private fun result(id: String) = MeetingRecordingResult(id, "m$id", "t", "요약 $id", emptyList(), emptyList())

    @Test
    fun everyResultIsDeliveredInOrder() = runBlocking {
        val (pending, _, q) = setup()
        q.add(pending, "a.m4a"); q.add(pending, "b.m4a"); q.add(pending, "c.m4a")
        val got = mutableListOf<String>()
        q.drain(upload = { result(it.file.name) }, onResult = { got += it.meetingId }, onPermanentFailure = { _, _ -> })
        assertEquals(listOf("a.m4a", "b.m4a", "c.m4a"), got)
        assertTrue(pending.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun permanentFailureMovesFileAsideAndContinues() = runBlocking {
        val (pending, failed, q) = setup()
        q.add(pending, "a.m4a"); q.add(pending, "b.m4a")
        val got = mutableListOf<String>()
        val failedItems = mutableListOf<String>()
        q.drain(
            upload = { if (it.file.name == "a.m4a") throw PermanentUploadFailure(422, "no speech was transcribed") else result(it.file.name) },
            onResult = { got += it.meetingId },
            onPermanentFailure = { item, e -> failedItems += "${item.file.name}:${e.code}" },
        )
        assertEquals(listOf("b.m4a"), got)
        assertEquals(listOf("a.m4a:422"), failedItems)
        assertTrue(File(failed, "a.m4a").exists())
        assertTrue(File(failed, "a.m4a.json").exists())
        assertTrue(pending.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun temporaryFailureStopsAndKeepsFiles() = runBlocking {
        val (pending, failed, q) = setup()
        q.add(pending, "a.m4a"); q.add(pending, "b.m4a")
        var calls = 0
        q.drain(upload = { calls++; throw QuotaExceeded("오늘 한도") }, onResult = {}, onPermanentFailure = { _, _ -> })
        assertEquals(1, calls)
        assertTrue(File(pending, "a.m4a").exists())
        assertTrue(File(pending, "b.m4a").exists())
        assertFalse(failed.exists() && failed.listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun oldFilesInBothFoldersAreDropped() = runBlocking {
        val root = Files.createTempDirectory("pending").toFile()
        val pending = File(root, "p"); val failed = File(root, "f").apply { mkdirs() }
        val q0 = PendingRecordingQueue(pending, failed)
        q0.add(pending, "old.m4a")
        File(failed, "gone.m4a").writeText("x")
        val later = PendingRecordingQueue(pending, failed, now = { System.currentTimeMillis() + PendingRecordingQueue.KEEP_MS + 60_000 })
        var calls = 0
        later.drain(upload = { calls++; result("x") }, onResult = {}, onPermanentFailure = { _, _ -> })
        assertEquals(0, calls)
        assertFalse(File(pending, "old.m4a").exists())
        assertFalse(File(failed, "gone.m4a").exists())
    }

    @Test
    fun permanentCodes() {
        assertTrue(PendingRecordingQueue.isPermanentFailure(422))
        assertTrue(PendingRecordingQueue.isPermanentFailure(413))
        assertTrue(PendingRecordingQueue.isPermanentFailure(415))
        for (code in listOf(400, 401, 403, 404, 405, 408, 429, 500, 503)) assertFalse("$code", PendingRecordingQueue.isPermanentFailure(code))
    }
}
