package com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks

import org.json.JSONObject

/** What the task list shows for each record and which buttons it offers. No Android dependencies. */
enum class TaskListSection(val label: String) {
    NEEDS_YOU("확인이 필요한 일"),
    RUNNING("진행 중"),
    KEPT("보관한 결과"),
    ENDED("멈추거나 실패한 일"),
}

enum class TaskListAction { APPROVE, EDIT, RESUME, HOLD, CANCEL, DELETE }

object TaskListRules {
    private val contactTools = setOf("call_contact", "text_contact")

    /** The count line under the camera; shown whenever records exist, in a session or not. */
    fun summary(tasks: List<TaskRecord>): String {
        if (tasks.isEmpty()) return ""
        val counts = tasks.groupingBy(::section).eachCount()
        return listOfNotNull(
            counts[TaskListSection.NEEDS_YOU]?.let { "확인/보류 $it" },
            counts[TaskListSection.RUNNING]?.let { "진행 $it" },
            counts[TaskListSection.KEPT]?.let { "결과 보관 $it" },
            counts[TaskListSection.ENDED]?.let { "멈춤/실패 $it" },
        ).joinToString(" · ")
    }

    fun section(task: TaskRecord): TaskListSection = when (task.state) {
        TaskState.WAITING_USER, TaskState.HELD, TaskState.OUTCOME_UNKNOWN -> TaskListSection.NEEDS_YOU
        TaskState.QUEUED, TaskState.RUNNING, TaskState.WAITING_NETWORK -> TaskListSection.RUNNING
        TaskState.COMPLETED -> TaskListSection.KEPT
        TaskState.FAILED, TaskState.CANCELLED -> TaskListSection.ENDED
    }

    /** Never-sent drafts and finished records can go. Queued, running or network-waiting work may still go out. */
    fun deletable(task: TaskRecord): Boolean = when (task.state) {
        TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.HELD, TaskState.OUTCOME_UNKNOWN -> true
        TaskState.WAITING_USER -> !task.dispatched
        TaskState.QUEUED, TaskState.RUNNING, TaskState.WAITING_NETWORK -> false
    }

    /**
     * The text the user may correct before approving: the message of a text, or the note of a save. Only a
     * draft that has not been sent can change ([TaskManager.editDraft] enforces the same).
     */
    fun editableField(task: TaskRecord): String? =
        if (task.state != TaskState.WAITING_USER || task.dispatched) null else mainField(task)

    /** The argument that says what the task is about, whatever its state. */
    fun mainField(task: TaskRecord): String? {
        val args = runCatching { JSONObject(task.argsJson) }.getOrNull() ?: return null
        val keys = when (task.tool) {
            "text_contact" -> listOf("message")
            "draft_mail_reply" -> listOf("body")
            "create_calendar_event" -> listOf("title")
            "save_person" -> listOf("notes_summary")
            "save_memory", "save_life_memory", "save_meeting", "save_need" -> listOf("user_note", "text", "summary")
            else -> emptyList()
        }
        return keys.firstOrNull { args.optString(it).let { v -> v.isNotBlank() && v != "null" } }
    }

    fun actions(task: TaskRecord, approvable: Boolean): List<TaskListAction> = buildList {
        when (task.state) {
            TaskState.WAITING_USER -> {
                if (!task.dispatched && (approvable || task.tool in contactTools)) add(TaskListAction.APPROVE)
                if (editableField(task) != null) add(TaskListAction.EDIT)
            }
            TaskState.QUEUED, TaskState.RUNNING -> { add(TaskListAction.HOLD); add(TaskListAction.CANCEL) }
            TaskState.HELD -> add(TaskListAction.RESUME)
            TaskState.FAILED -> if (!task.dispatched) add(TaskListAction.RESUME)
            else -> Unit
        }
        if (deletable(task)) add(TaskListAction.DELETE)
    }
}
