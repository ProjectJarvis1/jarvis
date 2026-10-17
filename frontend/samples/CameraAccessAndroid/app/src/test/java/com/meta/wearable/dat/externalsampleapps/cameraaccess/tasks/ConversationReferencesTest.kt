package com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks

import org.junit.Assert.*
import org.junit.Test

class ConversationReferencesTest {
    private fun photo(id: String, time: Long) = TaskRecord(id, tool = "capture_current_view", argsJson = "{}", title = id,
        createdAt = time, updatedAt = time, state = TaskState.COMPLETED)
    @Test fun ambiguousPhotosRequireClarificationAndOrdinalUsesCaptureOrder() {
        val tasks = listOf(photo("first", 1), photo("second", 2),
            TaskRecord("search", tool = "search_people", argsJson = "{}", title = "person search", createdAt = 3, updatedAt = 3,
                state = TaskState.COMPLETED, result = """[{"person":{"id":"person-id","name":"김민수"},"score":0.9}]"""))
        assertEquals("needs_clarification", ConversationReferences.resolve(tasks, "photo").getString("status"))
        assertEquals("second", ConversationReferences.resolve(tasks, "photo", ordinal = 2).getJSONArray("candidates").getJSONObject(0).getString("record_id"))
        assertEquals("person-id", ConversationReferences.resolve(tasks, "person", "김민수").getJSONArray("candidates").getJSONObject(0).getString("record_id"))
    }
    @Test fun cancelledAndFailedResultsAreNotReferences() {
        val tasks = listOf(photo("cancelled", 1).copy(state = TaskState.CANCELLED), photo("failed", 2).copy(state = TaskState.FAILED))
        assertEquals("not_found", ConversationReferences.resolve(tasks, "photo").getString("status"))
    }
    @Test fun sourcesKeepOriginalTimeAndImageWithoutExposingFilesystemPath() {
        val task = photo("source", 1).copy(tool = "search_memory", result = """[{"memory":{"id":"m1","text":"회의 근거","captured_at":"2026-10-01","metadata":{"image_path":"/private/data/picture.jpg"}}}]""")
        val source = ConversationReferences.from(listOf(task)).single()
        assertEquals("2026-10-01", source.timestamp)
        assertEquals("/memory/images/picture.jpg", source.imagePath)
        assertEquals("source", source.taskId)
    }
}
