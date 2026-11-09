package com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class TaskListTest {
    private class MemoryStore(var data: List<TaskRecord> = emptyList()) : TaskStore {
        override suspend fun load() = data
        override suspend fun save(tasks: List<TaskRecord>) { data = tasks }
    }
    private suspend fun TaskManager.until(id: String, state: TaskState): TaskRecord = withTimeout(2000) {
        tasks.first { list -> list.any { it.id == id && it.state == state } }.first { it.id == id }
    }
    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try { scope.block() } finally { scope.cancel() }
    }
    private fun record(state: TaskState, tool: String = "save_memory", args: String = "{}", dispatched: Boolean = false) =
        TaskRecord("t", tool = tool, argsJson = args, title = "t", createdAt = 0, updatedAt = 0, state = state, dispatched = dispatched)

    @Test fun deletesFinishedAndUnsentWorkButNotWorkThatMayStillGoOut() = test {
        val gate = CompletableDeferred<Unit>()
        val manager = TaskManager(this, MemoryStore(), { task, _ ->
            when (task.argsJson) {
                "wait" -> TaskOutcome(waitingForUser = true)
                "slow" -> { gate.await(); TaskOutcome(result = "late") }
                else -> TaskOutcome(result = "ok")
            }
        })
        manager.initialize()
        val done = manager.submit("save_memory", "fast", "완료")
        manager.until(done.id, TaskState.COMPLETED)
        manager.delete(done.id)
        assertNull(manager.get(done.id))

        val draft = manager.submit("save_memory", "wait", "확인 대기")
        manager.until(draft.id, TaskState.WAITING_USER)
        manager.delete(draft.id)
        assertNull(manager.get(draft.id))

        val running = manager.submit("search_memory", "slow", "검색")
        manager.until(running.id, TaskState.RUNNING)
        try { manager.delete(running.id); fail("running work must stay") } catch (_: IllegalArgumentException) { }
        manager.control(running.id, 1, "hold")
        manager.delete(running.id)
        gate.complete(Unit)
        assertNull(manager.get(running.id))   // a late result does not bring it back
    }

    @Test fun keepsAResultAnotherLiveTaskStillReads() = test {
        val manager = TaskManager(this, MemoryStore(), { task, _ ->
            if (task.sourceTaskId != null) TaskOutcome(waitingForUser = true) else TaskOutcome(result = "photo")
        })
        manager.initialize()
        val photo = manager.submit("capture_current_view", "{}", "사진")
        manager.until(photo.id, TaskState.COMPLETED)
        val user = manager.submit("save_memory", "{}", "저장", photo.id, 1)
        manager.until(user.id, TaskState.WAITING_USER)
        try { manager.delete(photo.id); fail("source in use") } catch (_: IllegalArgumentException) { }
        manager.delete(user.id)
        manager.delete(photo.id)
        assertTrue(manager.snapshot().isEmpty())
    }

    @Test fun offersOnlyTheButtonsThatStateAllows() {
        assertEquals(listOf(TaskListAction.APPROVE, TaskListAction.EDIT, TaskListAction.DELETE),
            TaskListRules.actions(record(TaskState.WAITING_USER, "text_contact", """{"query":"김대리","message":"3시에 봬요"}"""), approvable = false))
        assertEquals(listOf(TaskListAction.HOLD, TaskListAction.CANCEL), TaskListRules.actions(record(TaskState.RUNNING), true))
        assertEquals(listOf(TaskListAction.RESUME, TaskListAction.DELETE), TaskListRules.actions(record(TaskState.HELD), true))
        assertEquals(listOf(TaskListAction.DELETE), TaskListRules.actions(record(TaskState.OUTCOME_UNKNOWN, dispatched = true), true))
        assertEquals(emptyList<TaskListAction>(), TaskListRules.actions(record(TaskState.WAITING_NETWORK, dispatched = true), true))
        assertEquals("message", TaskListRules.editableField(record(TaskState.WAITING_USER, "text_contact", """{"message":"안녕"}""")))
        assertNull(TaskListRules.editableField(record(TaskState.COMPLETED, "text_contact", """{"message":"안녕"}""")))
        assertEquals(TaskListSection.NEEDS_YOU, TaskListRules.section(record(TaskState.HELD)))
        assertEquals("확인/보류 2 · 결과 보관 1", TaskListRules.summary(listOf(record(TaskState.HELD),
            record(TaskState.WAITING_USER), record(TaskState.COMPLETED))))
    }
}
