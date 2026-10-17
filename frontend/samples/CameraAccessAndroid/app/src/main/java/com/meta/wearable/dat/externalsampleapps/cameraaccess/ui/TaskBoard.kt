package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.*
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun TaskState.label() = when (this) {
    TaskState.QUEUED -> "접수됨"
    TaskState.RUNNING -> "진행 중"
    TaskState.WAITING_NETWORK -> "연결 복구 대기"
    TaskState.WAITING_USER -> "확인 필요"
    TaskState.HELD -> "보류"
    TaskState.COMPLETED -> "완료"
    TaskState.FAILED -> "실패"
    TaskState.CANCELLED -> "취소됨"
    TaskState.OUTCOME_UNKNOWN -> "실행 결과 확인 필요"
}

private val fieldLabels = mapOf("text" to "내용", "user_note" to "메모", "ai_interpretation" to "장면 설명",
    "people_text" to "관련 사람", "name" to "이름", "org" to "조직", "role" to "직책", "title" to "제목",
    "summary" to "요약", "notes_summary" to "메모", "start_at" to "시작 일시 (시간대 포함)", "description" to "설명",
    "phone" to "전화번호", "email" to "이메일", "location" to "장소")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskBoardButton(viewModel: GeminiSessionViewModel) {
    val tasks by viewModel.taskRecords.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val pending = tasks.count { it.state == TaskState.WAITING_USER }
    FilledTonalButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (pending > 0) "확인할 작업 $pending" else "작업 ${tasks.size}")
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        TaskBoardScreen(viewModel, Modifier.fillMaxHeight(.92f), onClose = { open = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskBoardScreen(viewModel: GeminiSessionViewModel, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val tasks by viewModel.taskRecords.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("전체") }
    val current = tasks.firstOrNull { it.id == selected }
    androidx.activity.compose.BackHandler(enabled = current != null) { selected = null }
    Column(modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 20.dp)) {
                Text(if (current == null) "작업" else "작업 상세", style = MaterialTheme.typography.headlineLarge)
                if (current == null) Text("맡긴 일의 진행 상황을 확인하세요", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (current != null) TextButton(onClick = { selected = null }) { Text("목록") }
            else if (onClose != null) TextButton(onClick = onClose) { Text("닫기") }
        }
        if (current != null) {
            val full = key(current.id, current.revision, current.updatedAt, current.state) {
                val loaded by produceState(current) { value = current.copy(result = viewModel.fullTaskResult(current)) }
                loaded
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) { TaskDetail(full, viewModel) }
        } else {
            val pending = tasks.count { it.state == TaskState.WAITING_USER }
            if (pending > 0) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("확인할 내용이 ${pending}개 있어요", style = MaterialTheme.typography.titleMedium)
                    Text("내용을 검토하면 저장과 수정을 진행할 수 있어요.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("전체", "확인 필요", "진행 중", "완료", "다시 확인").forEach { label ->
                    FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) })
                }
            }
            val visible = tasks.asReversed().filter { when (filter) {
                "확인 필요" -> it.state == TaskState.WAITING_USER
                "진행 중" -> it.state in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.WAITING_NETWORK)
                "완료" -> it.state == TaskState.COMPLETED
                "다시 확인" -> it.state in setOf(TaskState.FAILED, TaskState.HELD, TaskState.OUTCOME_UNKNOWN)
                else -> true
            } }
            if (visible.isEmpty()) {
                JarvisCard {
                    Text(if (tasks.isEmpty()) "부탁한 일을 여기에 모아둘게요" else "이 상태의 작업이 없어요", style = MaterialTheme.typography.titleMedium)
                    Text("자비스에게 촬영이나 검색을 요청하면 진행 상황과 결과를 확인할 수 있어요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                items(visible.size, key = { visible[it].id }) { index ->
                    val task = visible[index]
                    Surface(onClick = { selected = task.id }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(task.state.label(), color = if (task.state == TaskState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge)
                            Text(task.title, style = MaterialTheme.typography.titleMedium)
                            Text(DateTimeFormatter.ofPattern("M월 d일 a h:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(task.createdAt)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultImage(key: String, load: suspend () -> Bitmap?) {
    val bitmap by produceState<Bitmap?>(null, key) { value = load() }
    bitmap?.let { Image(it.asImageBitmap(), "원본 이미지", Modifier.fillMaxWidth().heightIn(max = 260.dp)) }
}

@Composable
private fun TaskDetail(task: TaskRecord, vm: GeminiSessionViewModel) {
    Text(task.title, style = MaterialTheme.typography.titleLarge)
    Text(task.state.label())
    task.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (task.state == TaskState.WAITING_NETWORK) Text("앱이 실행 중인 동안 연결을 확인하고 다시 전송합니다. 같은 요청은 중복 반영하지 않습니다.")
    if (task.state == TaskState.OUTCOME_UNKNOWN) Text("이미 전달된 요청일 수 있습니다. 새 요청을 만들기 전에 결과를 확인하세요.")
    if (task.tool == "capture_current_view" && task.state == TaskState.COMPLETED) ResultImage("${task.id}:${task.revision}") { vm.taskImage(task) }
    task.interpretation?.let { Text(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (task.state in setOf(TaskState.RUNNING, TaskState.QUEUED, TaskState.WAITING_USER, TaskState.HELD, TaskState.WAITING_NETWORK)) {
            TextButton(onClick = { vm.controlTask(task.id, task.revision, "cancel") }) { Text("취소") }
        }
        if (task.state in setOf(TaskState.HELD, TaskState.FAILED) && !task.dispatched) {
            Button(onClick = { vm.controlTask(task.id, task.revision, "resume") }) { Text("다시 시도") }
        }
    }
    if (task.state == TaskState.WAITING_USER && task.tool !in setOf("call_contact", "text_contact")) {
        var draft by remember(task.id, task.revision) { mutableStateOf(task.argsJson) }
        val obj = JSONObject(draft)
        if (task.tool == "save_person" && obj.optBoolean("include_face")) {
            Text("이름과 사진 속 얼굴을 함께 등록해요.", style = MaterialTheme.typography.titleMedium)
        }
        if (task.sourceTaskId != null && task.tool in setOf("save_person", "save_life_memory")) {
            val all by vm.taskRecords.collectAsStateWithLifecycle()
            val source = all.firstOrNull { it.id == task.sourceTaskId && it.revision == task.sourceRevision }
            if (source != null && source.tool == "capture_current_view") {
                ResultImage("${source.id}:${source.revision}") { vm.taskImage(source) }
            }
        }
        fieldLabels.forEach { (key, label) ->
            if (obj.has(key) && obj.opt(key) is String) {
                OutlinedTextField(value = obj.optString(key), onValueChange = { draft = JSONObject(draft).put(key, it).toString() },
                    label = { Text(label) }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (task.tool in setOf("apply_record_change", "undo_record_change")) {
            val changeId = obj.optString("change_id")
            val previewState = remember(changeId, task.revision) { mutableStateOf<JSONObject?>(null) }
            var previewError by remember(changeId, task.revision) { mutableStateOf<String?>(null) }
            LaunchedEffect(changeId, task.revision) {
                try { previewState.value = vm.readChange(changeId) }
                catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    previewError = e.message ?: "변경 내용을 불러오지 못했어요."
                }
            }
            val preview = previewState.value
            if (preview == null) {
                Text(previewError ?: "서버에서 변경 내용을 확인하고 있어요.")
                return
            }
            ChangePreview(preview, reverse = task.tool == "undo_record_change")
            val expected = if (task.tool == "undo_record_change") "applied" else "preview"
            if (preview.optString("state") != expected) {
                Text("변경 상태가 달라졌어요. 최신 기록을 다시 확인해주세요.")
                return
            }
        }
        if (draft != task.argsJson) {
            Button(onClick = { vm.editTask(task.id, task.revision, draft) }) { Text("초안 수정 반영") }
            Text("수정 내용을 반영한 뒤 승인할 수 있습니다.")
        } else {
            Button(onClick = { vm.controlTask(task.id, task.revision, "approve") }) { Text("내용 확인 · 실행 승인") }
        }
    }
    if (task.state != TaskState.COMPLETED) return
    val parsedJson = remember(task.result) { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(task.result) {
        parsedJson.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { JSONObject(task.result.orEmpty()) }.getOrNull()
        }
    }
    val json = parsedJson.value
    if (task.tool == "identify_person" && json != null) {
        Text(json.optString("guidance"), style = MaterialTheme.typography.bodyLarge)
        if (json.optString("decision") != "confident") Text("사진 속 사람의 신원이 확정되지 않았어요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (task.resultTruncated) Text("긴 결과는 원문으로 별도 보관했어요.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (task.tool == "prepare_record_change" && json != null) {
        ChangePreview(json)
        Button(onClick = { vm.proposeChange(json.getString("id")) }) { Text("이 변경으로 확인 요청") }
    }
    if (task.tool == "apply_record_change" && json != null && !json.optBoolean("deleted")) {
        Button(onClick = { vm.proposeChange(json.getString("change_id"), true) }) { Text("수정 되돌리기 요청") }
    }
    if (task.tool == "get_meeting_followups" && json != null) {
        val items = json.optJSONArray("items")
        if (items == null || items.length() == 0) Text("추출된 후속 업무가 없습니다.")
        for (i in 0 until (items?.length() ?: 0)) {
            val item = items!!.getJSONObject(i)
            FollowupCard(item, vm)
        }
    }
    val parsedReferences = remember(task.id, task.revision, task.result) { mutableStateOf<List<ConversationReference>>(emptyList()) }
    LaunchedEffect(task.id, task.revision, task.result) {
        parsedReferences.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            ConversationReferences.from(listOf(task)).filter { it.kind != "photo" }
        }
    }
    val references = parsedReferences.value
    references.forEach { reference -> EvidenceCard(reference, vm) }
    if (references.isEmpty() && task.tool !in setOf("prepare_record_change", "get_meeting_followups")) {
        Text(task.interpretation ?: if (json == null) task.result.orEmpty() else "처리 결과가 보관되었습니다.")
    }
}

@Composable
private fun ChangePreview(json: JSONObject, reverse: Boolean = false) {
    Text("변경 전", style = MaterialTheme.typography.titleSmall)
    val original = json.optJSONObject("before_data")?.optJSONObject("values")
    val afterData = json.optJSONObject("after_data")
    val changed = afterData?.optJSONObject("values")
    val before = if (reverse) changed else original
    val after = if (reverse) original else changed
    before?.keys()?.asSequence()?.forEach { key -> Text("${fieldLabels[key] ?: key}: ${before.optString(key)}") }
    Text("변경 후", style = MaterialTheme.typography.titleSmall)
    if (afterData?.optString("action") == "delete") Text("기억 삭제 — 이 삭제는 되돌릴 수 없습니다.")
    else after?.keys()?.asSequence()?.forEach { key -> Text("${fieldLabels[key] ?: key}: ${after.optString(key)}") }
}

@Composable
private fun EvidenceCard(reference: ConversationReference, vm: GeminiSessionViewModel) {
    var edit by remember(reference.recordId) { mutableStateOf(false) }
    var text by remember(reference.recordId) { mutableStateOf(if (reference.kind == "person") reference.title else reference.excerpt) }
    var version by remember(reference.recordId) { mutableStateOf<String?>(null) }
    var loadError by remember(reference.recordId) { mutableStateOf<String?>(null) }
    val field = when (reference.kind) { "person" -> "name"; "meeting" -> "summary"; else -> "text" }
    LaunchedEffect(edit, reference.recordId) {
        if (edit) {
            version = null
            loadError = null
            try {
                val latest = vm.readRecord(reference)
                text = latest.getJSONObject("values").optString(field)
                version = latest.getString("version")
            } catch (e: Exception) { loadError = e.message }
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(reference.title, style = MaterialTheme.typography.titleMedium)
            Text(reference.timestamp, style = MaterialTheme.typography.bodySmall)
            Text(reference.excerpt)
            Text("조회 당시 원본 기록 · ${reference.recordId.take(8)}", style = MaterialTheme.typography.labelSmall)
            if (reference.imagePath.isNotBlank()) ResultImage(reference.imagePath) { vm.evidenceImage(reference.imagePath) }
            TextButton(onClick = { edit = !edit }) { Text("기록 수정") }
            if (edit) {
                OutlinedTextField(text, { text = it }, label = { Text(if (reference.kind == "person") "이름" else "수정할 내용") })
                loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(enabled = version != null, onClick = {
                    vm.requestRecordChange(reference, JSONObject().put(field, text), expectedVersion = version)
                    edit = false
                }) { Text("변경 미리보기 만들기") }
            }
            if (reference.kind == "memory") TextButton(onClick = { vm.requestRecordChange(reference, JSONObject(), "delete") }) { Text("저장 취소 · 삭제 미리보기") }
            if (reference.kind == "meeting") Button(onClick = { vm.requestFollowups(reference.recordId) }) { Text("회의 후속 업무 보기") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FollowupCard(item: JSONObject, vm: GeminiSessionViewModel) {
    var start by remember(item.toString()) { mutableStateOf(item.optString("due_at")) }
    var chooseDate by remember { mutableStateOf(false) }
    var chooseTime by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<java.time.LocalDate?>(null) }
    val datePicker = rememberDatePickerState()
    val timePicker = rememberTimePickerState(initialHour = 9, is24Hour = true)
    if (chooseDate) DatePickerDialog(onDismissRequest = { chooseDate = false },
        confirmButton = { TextButton(enabled = datePicker.selectedDateMillis != null, onClick = {
            selectedDate = java.time.Instant.ofEpochMilli(datePicker.selectedDateMillis!!).atZone(java.time.ZoneOffset.UTC).toLocalDate()
            chooseDate = false; chooseTime = true
        }) { Text("시간 선택") } }, dismissButton = { TextButton(onClick = { chooseDate = false }) { Text("취소") } }) {
        DatePicker(state = datePicker)
    }
    if (chooseTime) AlertDialog(onDismissRequest = { chooseTime = false }, title = { Text("언제 시작할까요?") },
        text = { TimeInput(state = timePicker) }, confirmButton = { TextButton(onClick = {
            start = selectedDate!!.atTime(timePicker.hour, timePicker.minute).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
            chooseTime = false
        }) { Text("선택 완료") } }, dismissButton = { TextButton(onClick = { chooseTime = false }) { Text("취소") } })
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.optString("task"), style = MaterialTheme.typography.titleMedium)
            Text("담당자: ${item.optString("owner").ifBlank { "미정" }}")
            Text("근거: ${item.optString("evidence").ifBlank { "기존 회의 요약 — 원문을 확인하세요" }}")
            val display = runCatching { java.time.OffsetDateTime.parse(start).format(DateTimeFormatter.ofPattern("M월 d일 HH:mm")) }.getOrDefault("날짜와 시간 선택")
            OutlinedButton(onClick = { chooseDate = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(display) }
            Text("시간대: ${ZoneId.systemDefault().id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.optString("due_text").takeIf { it.isNotBlank() }?.let { Text("회의에서 언급한 기한: $it") }
            val valid = runCatching { java.time.OffsetDateTime.parse(start) }.isSuccess
            Button(enabled = valid, onClick = { vm.proposeCalendar(item.optString("task"), start,
                "담당자: ${item.optString("owner")}\n근거: ${item.optString("evidence")}") }) { Text("일정 등록 확인 요청") }
            if (!valid) Text("날짜와 시간을 선택하면 일정 등록을 요청할 수 있어요.")
        }
    }
}
