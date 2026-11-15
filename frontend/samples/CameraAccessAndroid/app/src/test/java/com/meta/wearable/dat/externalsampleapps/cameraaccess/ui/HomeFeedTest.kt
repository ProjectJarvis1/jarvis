package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskRecord
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private fun task(id: String, state: TaskState, ago: Long) =
        TaskRecord(id = id, tool = "save_memory", argsJson = "{}", title = id, createdAt = now - ago, updatedAt = now - ago, state = state)

    @Test
    fun keepsEverythingWaitingOnTheUserOrRunning_evenWhenOld() {
        val shown = homeTasks(listOf(task("wait", TaskState.WAITING_USER, 72 * hour), task("run", TaskState.RUNNING, 30 * hour),
            task("net", TaskState.WAITING_NETWORK, 2 * hour), task("held", TaskState.HELD, 50 * hour)), now).map { it.id }
        assertEquals(setOf("wait", "run", "net", "held"), shown.toSet())
    }

    @Test
    fun showsOnlyTheLastDaysEndings_newestThreeKeptAndTwoFailed() {
        val kept = (1..5).map { task("kept$it", TaskState.COMPLETED, it * hour) }
        val failed = (1..3).map { task("fail$it", TaskState.FAILED, it * hour) }
        val old = listOf(task("oldKept", TaskState.COMPLETED, 25 * hour), task("oldFail", TaskState.FAILED, 30 * hour))
        val shown = homeTasks(kept + failed + old + task("cancel", TaskState.CANCELLED, hour), now).map { it.id }
        assertEquals(listOf("kept1", "kept2", "kept3", "fail1", "fail2"), shown)
        assertFalse("cancel" in shown)
    }

    @Test
    fun emptyWhenThereIsNothing() {
        assertTrue(homeTasks(emptyList(), now).isEmpty())
    }
}
