package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ExternalTools
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.toolLabel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskListAction
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskListRules
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskListSection
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskRecord
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONObject

/** What the list needs from the session, so the sheet stays a plain view of the task records. */
class TaskListHandlers(
    val approvable: (TaskRecord) -> Boolean,
    val approve: (TaskRecord) -> Unit,
    val control: (TaskRecord, String) -> Unit,
    val edit: (TaskRecord, String) -> Unit,
    val delete: (TaskRecord) -> Unit,
)

/**
 * Every task Jarvis kept, grouped by what the user has to do about it. Opened from the count line on the
 * conversation screen; the same records Jarvis reads when asked "아까 한 거 뭐 있어?".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListSheet(tasks: List<TaskRecord>, handlers: TaskListHandlers, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Jarvis.extras.card) {
        val grouped = remember(tasks) {
            tasks.sortedByDescending { it.updatedAt }.groupBy(TaskListRules::section).toSortedMap(compareBy { it.ordinal })
        }
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Text("작업 기록", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp))
                Text("하루가 지난 완료 기록은 저절로 정리돼요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tasks.isEmpty()) item {
                Text("아직 남은 작업이 없어요. 자비스에게 무언가 부탁하면 여기에 쌓여요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp))
            }
            grouped.forEach { (section, list) ->
                item(key = "h-${section.name}") {
                    Text("${section.label} ${list.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 28.dp, bottom = 4.dp))
                }
                items(list, key = { "${it.id}-${it.revision}" }) { task ->
                    TaskRow(task, handlers)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskRow(task: TaskRecord, handlers: TaskListHandlers) {
    var mode by remember(task.id, task.revision) { mutableStateOf(RowMode.VIEW) }
    val field = remember(task.argsJson, task.state) { TaskListRules.editableField(task) }
    val args = remember(task.argsJson) { runCatching { JSONObject(task.argsJson) }.getOrNull() }
    var draft by remember(task.id, task.revision) { mutableStateOf(field?.let { args?.optString(it) }.orEmpty()) }
    val actions = TaskListRules.actions(task, handlers.approvable(task))

    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(task.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stateLabel(task.state), style = MaterialTheme.typography.labelMedium,
                color = if (task.state in attention) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${toolLabel(task.tool)} · ${whenLabel(task.updatedAt)}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        detail(task, args)?.let {
            // Nothing awaiting approval is clipped: the user approves exactly what they read.
            Text(it, style = MaterialTheme.typography.bodyMedium,
                maxLines = if (task.state == TaskState.WAITING_USER) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
        }

        // Approved by voice while the edit box was open: the draft is gone, so is the edit.
        if (mode == RowMode.EDIT && field == null) mode = RowMode.VIEW
        when (mode) {
            RowMode.EDIT -> if (field != null) {
                OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
                    label = { Text(fieldLabel(field)) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton({ mode = RowMode.VIEW }) { Text("그만두기") }
                    TextButton({
                        val current = TaskListRules.editableField(task)
                        if (current != null && args != null) handlers.edit(task, JSONObject(args.toString()).put(current, draft.trim()).toString())
                        mode = RowMode.VIEW
                    }, enabled = draft.isNotBlank()) { Text("고친 내용 저장") }
                }
            }
            RowMode.CONFIRM_DELETE -> Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(if (task.state == TaskState.OUTCOME_UNKNOWN)
                            "실제로 처리됐는지 확인한 뒤에만 지우세요. 지우면 이 기록이 남지 않아요."
                        else "이 기록을 지울까요? 되돌릴 수 없어요.",
                        style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton({ mode = RowMode.VIEW }) { Text("그대로 두기") }
                        TextButton({ handlers.delete(task); mode = RowMode.VIEW }) {
                            Text("지우기", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            RowMode.VIEW -> if (actions.isNotEmpty()) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                actions.forEach { action ->
                    TextButton({
                        when (action) {
                            TaskListAction.APPROVE -> handlers.approve(task)
                            TaskListAction.EDIT -> mode = RowMode.EDIT
                            TaskListAction.RESUME -> handlers.control(task, "resume")
                            TaskListAction.HOLD -> handlers.control(task, "hold")
                            TaskListAction.CANCEL -> handlers.control(task, "cancel")
                            TaskListAction.DELETE -> mode = RowMode.CONFIRM_DELETE
                        }
                    }) {
                        Text(actionLabel(action, task), color = if (action == TaskListAction.DELETE)
                            MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

private enum class RowMode { VIEW, EDIT, CONFIRM_DELETE }

private val attention = setOf(TaskState.WAITING_USER, TaskState.HELD, TaskState.OUTCOME_UNKNOWN, TaskState.FAILED)

private fun stateLabel(state: TaskState) = when (state) {
    TaskState.QUEUED -> "기다리는 중"
    TaskState.RUNNING -> "진행 중"
    TaskState.WAITING_NETWORK -> "연결되면 보내요"
    TaskState.WAITING_USER -> "확인 기다림"
    TaskState.HELD -> "보류"
    TaskState.COMPLETED -> "완료"
    TaskState.FAILED -> "실패"
    TaskState.CANCELLED -> "취소함"
    TaskState.OUTCOME_UNKNOWN -> "처리 여부 모름"
}

private fun actionLabel(action: TaskListAction, task: TaskRecord) = when (action) {
    TaskListAction.APPROVE -> when (task.tool) {
        "call_contact" -> "전화 걸기"
        "text_contact" -> "문자 앱 열기"
        "create_calendar_event" -> "일정 등록"
        else -> "확인"
    }
    TaskListAction.EDIT -> "고치기"
    TaskListAction.RESUME -> "다시 하기"
    TaskListAction.HOLD -> "보류"
    TaskListAction.CANCEL -> "취소"
    TaskListAction.DELETE -> "지우기"
}

private fun fieldLabel(field: String?) = when (field) {
    "message" -> "보낼 문자"
    "title" -> "일정 제목"
    "notes_summary" -> "메모"
    else -> "저장할 내용"
}

/** One line of what the task holds: the draft to approve, the kept result, or why it stopped. */
private fun detail(task: TaskRecord, args: JSONObject?): String? {
    fun arg(key: String) = args?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }
    return when (task.state) {
        TaskState.WAITING_USER, TaskState.HELD -> when {
            task.tool == "text_contact" -> listOfNotNull(arg("query")?.let { "받는 사람 $it" }, arg("message")).joinToString("\n")
            task.tool == "call_contact" -> arg("query")?.let { "받는 사람 $it" }
            // What goes to an outside service is shown exactly as the card shows it, before any approval.
            ExternalTools.isExternal(task.tool) -> ExternalTools.preview(task.tool, task.argsJson)
            task.tool == "save_person" -> listOfNotNull(arg("name"), arg("org"), arg("role"), arg("notes_summary"),
                if (args?.optBoolean("include_face") == true) "얼굴도 함께 저장해요. 상대방이 동의했을 때만 승인해 주세요." else null).joinToString("\n")
            task.tool == "create_calendar_event" -> com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.CalendarEventPreview.lines(task.argsJson).joinToString("\n")
            task.tool in setOf("apply_record_change", "undo_record_change") -> "대화 화면의 확인 카드에서 바뀌는 내용을 보고 승인해 주세요."
            else -> TaskListRules.mainField(task)?.let(::arg) ?: arg("query")
        }
        TaskState.OUTCOME_UNKNOWN -> task.error ?: "보냈는지 확인되지 않았어요. 받는 쪽에서 직접 확인해 주세요."
        TaskState.FAILED -> task.error
        TaskState.COMPLETED -> task.interpretation ?: task.result?.let(::readable)
        else -> null
    }?.takeIf { it.isNotBlank() }
}

/** Server results are JSON; show the sentence inside rather than braces. */
private fun readable(raw: String): String {
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return raw.take(200)
    return listOf("summary", "message", "text", "result", "answer").firstNotNullOfOrNull { key ->
        json.optString(key).takeIf { it.isNotBlank() && it != "null" && !it.startsWith("{") && !it.startsWith("[") }
    } ?: "결과를 보관했어요. 자비스에게 물으면 자세히 알려 줘요."
}

private val KST = ZoneId.of("Asia/Seoul")
private val TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(KST)
private val DAY_TIME = DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(KST)

private fun whenLabel(epochMs: Long): String {
    val at = Instant.ofEpochMilli(epochMs)
    return if (at.atZone(KST).toLocalDate() == LocalDate.now(KST)) "오늘 ${TIME.format(at)}" else DAY_TIME.format(at)
}
