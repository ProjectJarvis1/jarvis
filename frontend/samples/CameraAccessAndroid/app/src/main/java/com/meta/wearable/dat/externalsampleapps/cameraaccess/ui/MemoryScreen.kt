package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.FilledIconButton
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups2
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingFollowupStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingFollowups
import com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingHistoryItem
import com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingHistoryViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.memory.MemoryItem
import com.meta.wearable.dat.externalsampleapps.cameraaccess.memory.MemoryRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.memory.MemoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class RecordsTab(val title: String) {
    Memories("기억"),
    Meetings("회의"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    memoryViewModel: MemoryViewModel = viewModel(),
    meetingViewModel: MeetingHistoryViewModel = viewModel(),
) {
    val memoryUiState by memoryViewModel.uiState.collectAsStateWithLifecycle()
    val meetingUiState by meetingViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedTab by rememberSaveable { mutableStateOf(RecordsTab.Memories) }
    var selectedMemory by remember { mutableStateOf<MemoryItem?>(null) }
    var selectedMeeting by remember { mutableStateOf<MeetingHistoryItem?>(null) }
    var editingMemory by remember { mutableStateOf<MemoryItem?>(null) }
    var editingMeeting by remember { mutableStateOf<MeetingHistoryItem?>(null) }
    var pendingDeleteMemory by remember { mutableStateOf<MemoryItem?>(null) }
    var pendingDeleteMeeting by remember { mutableStateOf<MeetingHistoryItem?>(null) }

    LaunchedEffect(Unit) {
        memoryViewModel.loadRecent()
        meetingViewModel.loadRecent()
    }

    val currentTitle = when {
        selectedMemory != null -> "기억 상세"
        selectedMeeting != null -> "회의 상세"
        else -> "기억"
    }

    BackHandler(enabled = selectedMemory != null || selectedMeeting != null) {
        when {
            selectedMemory != null -> selectedMemory = null
            selectedMeeting != null -> selectedMeeting = null
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Jarvis.extras.ground,
        topBar = {
            TopAppBar(
                title = { Text(currentTitle, style = MaterialTheme.typography.headlineMedium) },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = Jarvis.extras.ground),
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when {
                                selectedMemory != null -> selectedMemory = null
                                selectedMeeting != null -> selectedMeeting = null
                                else -> onBack()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "이전 화면")
                    }
                },
                actions = {
                    when {
                        selectedMemory != null -> {
                            IconButton(onClick = { editingMemory = selectedMemory }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit memory")
                            }
                            IconButton(onClick = { pendingDeleteMemory = selectedMemory }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete memory")
                            }
                        }
                        selectedMeeting != null -> {
                            IconButton(onClick = { editingMeeting = selectedMeeting }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit meeting")
                            }
                            IconButton(onClick = {
                                selectedMeeting?.let { meeting ->
                                    val shareText = meetingViewModel.shareText(meeting)
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "회의 공유"))
                                }
                            }) {
                                Icon(Icons.Default.Share, contentDescription = "Share meeting")
                            }
                            IconButton(onClick = { pendingDeleteMeeting = selectedMeeting }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete meeting")
                            }
                        }
                        else -> {
                            IconButton(
                                onClick = {
                                    when (selectedTab) {
                                        RecordsTab.Memories -> memoryViewModel.refreshCurrent()
                                        RecordsTab.Meetings -> meetingViewModel.loadRecent()
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            selectedMemory != null -> {
                MemoryDetail(
                    memory = selectedMemory!!,
                    modifier = Modifier.padding(padding),
                )
            }
            selectedMeeting != null -> {
                MeetingDetail(
                    meeting = selectedMeeting!!,
                    modifier = Modifier.padding(padding),
                )
            }
            else -> {
                fun share(meeting: MeetingHistoryItem) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, meetingViewModel.shareText(meeting))
                    }
                    context.startActivity(Intent.createChooser(intent, "회의 공유"))
                }
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                    Text("기억과 회의를 한곳에서 찾아보세요.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    // The switch stays put when the search box (memories only) comes and goes under it.
                    JarvisSegmented(RecordsTab.entries.map { it.title }, selectedTab.ordinal, { selectedTab = RecordsTab.entries[it] })
                    Spacer(Modifier.height(10.dp))
                    if (selectedTab == RecordsTab.Memories) {
                        SearchBar(value = memoryUiState.query, onValueChange = memoryViewModel::updateQuery, onSearch = memoryViewModel::search)
                        Spacer(Modifier.height(4.dp))
                    }
                    when (selectedTab) {
                        RecordsTab.Memories -> RecordsState(
                            isLoading = memoryUiState.isLoading,
                            errorMessage = memoryUiState.errorMessage,
                            emptyText = "아직 저장된 기억이 없어요. 대화에서 \"이거 기억해 줘\"라고 말해 보세요.",
                            isEmpty = memoryUiState.memories.isEmpty(),
                        ) {
                            DayGroupedList(memoryUiState.memories, { it.capturedAt }, { it.id },
                                byDay = !memoryUiState.isSearchResult) { memory ->
                                JarvisRow(
                                    title = memory.userNote ?: memory.text.lineSequence().firstOrNull().orEmpty(),
                                    subtitle = "${memory.memoryTypeLabel()} · ${memory.capturedAtDisplay}",
                                    icon = memory.typeIcon(),
                                    iconTint = MaterialTheme.colorScheme.primary,
                                    iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                    onClick = { selectedMemory = memory },
                                )
                            }
                        }
                        RecordsTab.Meetings -> RecordsState(
                            isLoading = meetingUiState.isLoading,
                            errorMessage = meetingUiState.errorMessage,
                            emptyText = "아직 저장된 회의가 없어요. 대화 화면에서 회의 녹음을 시작해 보세요.",
                            isEmpty = meetingUiState.meetings.isEmpty(),
                        ) {
                            DayGroupedList(meetingUiState.meetings, { it.startedAt }, { it.id }) { meeting ->
                                JarvisRow(
                                    title = meeting.title,
                                    subtitle = listOfNotNull(meeting.timeDisplay, meeting.durationDisplay).joinToString(" · "),
                                    icon = Icons.Default.Groups2,
                                    iconTint = MaterialTheme.colorScheme.primary,
                                    iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                    onClick = { selectedMeeting = meeting },
                                    trailing = {
                                        IconButton(onClick = { share(meeting) }) {
                                            Icon(Icons.Default.Share, "회의 공유", Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editingMemory?.let { current ->
        MemoryEditDialog(
            memory = current,
            isSaving = memoryUiState.isSaving,
            onDismiss = { editingMemory = null },
            onSave = { updated ->
                memoryViewModel.updateMemory(updated) {
                    selectedMemory = it
                    editingMemory = null
                }
            },
        )
    }

    editingMeeting?.let { current ->
        MeetingEditDialog(
            meeting = current,
            isSaving = meetingUiState.isSaving,
            onDismiss = { editingMeeting = null },
            onSave = { updated ->
                meetingViewModel.updateMeeting(updated) {
                    selectedMeeting = it
                    editingMeeting = null
                }
            },
        )
    }

    pendingDeleteMemory?.let { memory ->
        DeleteDialog(
            title = "기억 삭제",
            body = "이 기억을 삭제하면 되돌릴 수 없습니다.",
            isSaving = memoryUiState.isSaving,
            onDismiss = { pendingDeleteMemory = null },
            onConfirm = {
                memoryViewModel.deleteMemory(memory.id) {
                    if (selectedMemory?.id == memory.id) selectedMemory = null
                    pendingDeleteMemory = null
                }
            },
        )
    }

    pendingDeleteMeeting?.let { meeting ->
        DeleteDialog(
            title = "회의 삭제",
            body = "이 회의와 연결된 회의 메모도 함께 삭제됩니다.",
            isSaving = meetingUiState.isSaving,
            onDismiss = { pendingDeleteMeeting = null },
            onConfirm = {
                meetingViewModel.deleteMeeting(meeting.id) {
                    if (selectedMeeting?.id == meeting.id) selectedMeeting = null
                    pendingDeleteMeeting = null
                }
            },
        )
    }
}

@Composable
private fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = if (value.isNotBlank()) { {
            IconButton(onClick = onSearch) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "검색", tint = MaterialTheme.colorScheme.primary) }
        } } else null,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
        shape = RoundedCornerShape(12.dp),
        placeholder = { Text("어떤 기억을 찾을까요?", maxLines = 1) },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Jarvis.extras.card, unfocusedContainerColor = Jarvis.extras.card,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Splits a list into 오늘 / 어제 / 이번 주 / 이전 by its KST timestamp and shows each part as one white group.
 * Search results are not split ([byDay] false): they stay in the server's relevance order under one heading.
 */
@Composable
private fun <T> DayGroupedList(items: List<T>, timeOf: (T) -> String, keyOf: (T) -> String, byDay: Boolean = true,
    row: @Composable (T) -> Unit) {
    val groups = remember(items, byDay) {
        if (!byDay) mapOf("검색 결과" to items)
        else items.groupBy { dayGroup(timeOf(it)) }.toSortedMap(compareBy { DAY_ORDER.indexOf(it) })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        groups.forEach { (day, rows) ->
            item(key = "day-$day") {
                Text(day, Modifier.padding(start = 4.dp, top = 14.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item(key = "group-$day") {
                JarvisGroup {
                    rows.forEachIndexed { i, item ->
                        androidx.compose.runtime.key(keyOf(item)) { row(item) }
                        if (i < rows.lastIndex) HorizontalDivider(Modifier.padding(start = 62.dp, end = 14.dp),
                            color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

private val DAY_ORDER = listOf("오늘", "어제", "이번 주", "이전")

private fun dayGroup(iso: String): String {
    val zone = java.time.ZoneId.of("Asia/Seoul")
    val date = runCatching { java.time.OffsetDateTime.parse(iso).atZoneSameInstant(zone).toLocalDate() }
        .recoverCatching { java.time.Instant.parse(iso).atZone(zone).toLocalDate() }.getOrNull() ?: return "이전"
    val today = java.time.LocalDate.now(zone)
    return when {
        date == today -> "오늘"
        date == today.minusDays(1) -> "어제"
        // Calendar week starting Monday, not "the last seven days".
        !date.isBefore(today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))) -> "이번 주"
        else -> "이전"
    }
}

private fun MemoryItem.typeIcon(): androidx.compose.ui.graphics.vector.ImageVector = when (memoryTypeLabel()) {
    "명함" -> Icons.Default.Badge
    "문서" -> Icons.Default.Description
    "사람" -> Icons.Default.Person
    "장소" -> Icons.Default.Place
    "음성 기억" -> Icons.Default.Mic
    else -> Icons.Default.PhotoCamera
}

@Composable
private fun RecordsState(
    isLoading: Boolean,
    errorMessage: String?,
    isEmpty: Boolean,
    emptyText: String,
    content: @Composable () -> Unit,
) {
    when {
        isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        errorMessage != null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = errorMessage, Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        isEmpty -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = emptyText, Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        else -> content()
    }
}

@Composable
private fun MemoryDetail(
    memory: MemoryItem,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            MemoryThumbnail(filename = memory.imageFilename, large = true)
        }
        item {
            MemoryRecallHeader(memory = memory)
        }
        item {
            DetailSection("사용자 메모", memory.userNote ?: memory.text)
        }
        memory.aiInterpretation?.let {
            item {
                IconDetailSection(
                    title = "AI 장면 해석",
                    body = it,
                    icon = {
                        Icon(
                            imageVector = Icons.Default.ImageSearch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
        }
        memory.peopleText?.let {
            item {
                IconDetailSection(
                    title = "관련 사람",
                    body = it,
                    icon = {
                        Icon(
                            imageVector = Icons.Default.PersonSearch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
        }
        item {
            RecallExamplesCard(memory = memory)
        }
        item {
            DetailCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("기본 정보", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    MetadataRow("저장 시간", memory.capturedAtDisplay)
                    MetadataRow("출처", memory.source)
                    MetadataRow("유형", memory.memoryTypeLabel())
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryRecallHeader(memory: MemoryItem) {
    DetailCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "이 기억",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = memory.userNote ?: memory.aiInterpretation ?: memory.text,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${memory.capturedAtDisplay}에 저장된 ${memory.memoryTypeLabel()}입니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                memory.labels.take(6).forEach { label ->
                    JarvisTag(label)
                }
            }
        }
    }
}

@Composable
private fun RecallExamplesCard(memory: MemoryItem) {
    val examples = memory.recallExamples()
    DetailCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "이렇게 다시 물어보세요",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            examples.forEach { example ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = example,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeetingDetail(
    meeting: MeetingHistoryItem,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(top = 4.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(meeting.title, style = MaterialTheme.typography.headlineSmall.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Paragraph.copy(wordBreak = androidx.compose.ui.text.style.LineBreak.WordBreak.Phrase)))
                Text(listOfNotNull(meeting.timeDisplay, meeting.durationDisplay?.let { "소요 시간 $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            MarkdownDetailCard(
                title = "회의 요약",
                skipHeading = meeting.title,
                body = meeting.markdownSummary ?: meeting.summary.orEmpty(),
            )
        }
        if (meeting.followups.isNotEmpty()) {
            item { FollowupCard(meeting) }
        }
        if (meeting.decisions.isNotEmpty()) {
            item {
                StructuredListCard(
                    title = "결정 사항",
                    items = meeting.decisions,
                    icon = {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
        }
        if (meeting.keywords.isNotEmpty()) {
            item {
                DetailCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("키워드", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            meeting.keywords.forEach { keyword ->
                                JarvisTag("#$keyword")
                            }
                        }
                    }
                }
            }
        }
        if (meeting.transcript?.isNotBlank() == true) {
            item {
                // The transcript is long; it opens on request, like the Figma "회의록 원문 보기" button.
                var open by rememberSaveable(meeting.id) { mutableStateOf(false) }
                if (open) DetailSection("회의록 원문", meeting.transcript)
                else JarvisSecondaryButton("회의록 원문 보기", { open = true }, Modifier.fillMaxWidth())
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/**
 * The meeting's follow-ups, each with what to do next: mark it done, or put it in the calendar on the
 * day the meeting named. Nothing is written or sent from here; the calendar and messaging apps open with
 * the content filled in and the user saves or sends there.
 */
@Composable
private fun FollowupCard(meeting: MeetingHistoryItem) {
    val context = LocalContext.current
    val start = remember(meeting.startedAt) { MeetingFollowups.parseStart(meeting.startedAt) }
    var version by remember(meeting.id) { mutableStateOf(0) }
    DetailCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.TaskAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("후속 할 일", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (meeting.followupsNeedReview) {
                Text("회의 원문이 바뀌었어요. 아래 할 일과 기한이 지금 원문과 맞는지 다시 확인해 주세요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            meeting.followups.forEachIndexed { index, item ->
                val steps = remember(version) { MeetingFollowupStatus.read(context, meeting.id, index) }
                val done = MeetingFollowupStatus.Step.DONE in steps
                val due = start?.let { MeetingFollowups.dueDate(item, it) }
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            androidx.compose.material3.Checkbox(checked = done, onCheckedChange = {
                                MeetingFollowupStatus.set(context, meeting.id, index, MeetingFollowupStatus.Step.DONE, it)
                                version++
                            })
                            Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.task, style = MaterialTheme.typography.bodyMedium,
                                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                    textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                                val meta = listOfNotNull(
                                    item.owner.takeIf { it.isNotBlank() }?.let { "담당 $it" },
                                    when {
                                        due != null && start != null -> MeetingFollowups.dueLabel(due, start)
                                        item.dueText.isNotBlank() -> "${item.dueText} (날짜 확인 필요)"
                                        else -> null
                                    },
                                ).joinToString(", ")
                                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                if (item.stale) Text("회의 원문을 고친 뒤 이 할 일의 근거 문장이 없어졌어요. 아직 할 일인지 확인해 주세요.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                if (item.evidence.isNotBlank()) Text("\u201C${item.evidence}\u201D", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (MeetingFollowupStatus.Step.CALENDAR_OPENED in steps) Text("일정 화면을 열었어요. 저장했는지는 캘린더에서 확인해 주세요.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (!done) TextButton(onClick = {
                            if (openFollowupCalendar(context, meeting, item, due)) {
                                MeetingFollowupStatus.set(context, meeting.id, index, MeetingFollowupStatus.Step.CALENDAR_OPENED, true)
                                version++
                            }
                        }, modifier = Modifier.padding(start = 40.dp)) { Text(if (due != null) "일정에 넣기" else "날짜 골라 일정에 넣기") }
                    }
                }
            }
            val draft = remember(meeting.id, meeting.decisions, meeting.followups, start) { MeetingFollowups.draftMessage(meeting.decisions, meeting.followups, start) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JarvisSecondaryButton("문자 초안", {
                    if (openDraft(context, android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:"))
                            .putExtra("sms_body", draft))) { MeetingFollowupStatus.markMessageDrafted(context, meeting.id); version++ }
                }, Modifier.weight(1f))
                JarvisSecondaryButton("메일 초안", {
                    if (openDraft(context, android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:"))
                            .putExtra(android.content.Intent.EXTRA_SUBJECT, "${meeting.title} 정리")
                            .putExtra(android.content.Intent.EXTRA_TEXT, draft))) { MeetingFollowupStatus.markMessageDrafted(context, meeting.id); version++ }
                }, Modifier.weight(1f))
            }
            val drafted = remember(version) { MeetingFollowupStatus.messageDrafted(context, meeting.id) }
            Text(
                if (drafted > 0) "${java.time.format.DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(java.time.ZoneId.of("Asia/Seoul"))
                    .format(java.time.Instant.ofEpochMilli(drafted))}에 초안을 열었어요. 받는 사람과 보내기는 문자나 메일 앱에서 직접 해요."
                else "받는 사람을 고르고 보내기를 누르는 건 문자나 메일 앱에서 직접 해요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun openFollowupCalendar(context: android.content.Context, meeting: MeetingHistoryItem,
    item: com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingFollowup, due: java.time.LocalDate?): Boolean {
    val intent = android.content.Intent(android.content.Intent.ACTION_INSERT)
        .setData(android.provider.CalendarContract.Events.CONTENT_URI)
        .putExtra(android.provider.CalendarContract.Events.TITLE, item.task)
        .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, buildString {
            append("${meeting.title}에서 정한 할 일")
            if (item.evidence.isNotBlank()) append("\n\u201C${item.evidence}\u201D")
            if (due == null) append("\n기한을 확인해 주세요.")
        })
    if (due != null) {
        // All-day events are stored at UTC midnight; a KST midnight would land on the day before.
        val begin = due.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        intent.putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, begin + 86_400_000L)
    }
    return openDraft(context, intent)
}

/** Opens another app with content filled in; false when no app can take it. */
private fun openDraft(context: android.content.Context, intent: android.content.Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: android.content.ActivityNotFoundException) {
    android.widget.Toast.makeText(context, "열 수 있는 앱이 없어요.", android.widget.Toast.LENGTH_SHORT).show()
    false
}

/** White card on the grey ground, the detail screens' only container. */
@Composable
private fun DetailCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = Jarvis.extras.card) {
        Column(content = content)
    }
}

@Composable
private fun DetailSection(
    title: String,
    body: String,
) {
    DetailCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun IconDetailSection(
    title: String,
    body: String,
    icon: @Composable () -> Unit,
) {
    DetailCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon()
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StructuredListCard(
    title: String,
    items: List<String>,
    icon: @Composable () -> Unit,
) {
    DetailCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon()
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items.forEach { item ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = item,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MarkdownDetailCard(
    title: String,
    body: String,
    // The screen header already shows the meeting title; a first heading that repeats it is dropped.
    skipHeading: String? = null,
) {
    val byWord = androidx.compose.ui.text.style.LineBreak.Paragraph.copy(
        wordBreak = androidx.compose.ui.text.style.LineBreak.WordBreak.Phrase)
    DetailCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            body.lineSequence()
                .filterNot { it.trim().startsWith("# ") && it.trim().removePrefix("# ").trim() == skipHeading?.trim() }
                .dropWhile { it.isBlank() }
                .forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("# ") -> Text(
                        trimmed.removePrefix("# ").trim(),
                        style = MaterialTheme.typography.titleLarge.copy(lineBreak = byWord),
                    )
                    trimmed.startsWith("## ") -> Text(
                        trimmed.removePrefix("## ").trim(),
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.titleSmall.copy(lineBreak = byWord),
                    )
                    trimmed.startsWith("- ") -> Text(
                        "• ${trimmed.removePrefix("- ").trim()}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    trimmed.isBlank() -> Spacer(modifier = Modifier.height(2.dp))
                    else -> Text(trimmed, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun MemoryEditDialog(
    memory: MemoryItem,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (MemoryItem) -> Unit,
) {
    var userNote by remember(memory) { mutableStateOf(memory.userNote.orEmpty()) }
    var aiInterpretation by remember(memory) { mutableStateOf(memory.aiInterpretation.orEmpty()) }
    var peopleText by remember(memory) { mutableStateOf(memory.peopleText.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("기억 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = userNote, onValueChange = { userNote = it }, label = { Text("메모") })
                OutlinedTextField(
                    value = aiInterpretation,
                    onValueChange = { aiInterpretation = it },
                    label = { Text("AI 해석") },
                )
                OutlinedTextField(value = peopleText, onValueChange = { peopleText = it }, label = { Text("관련 사람") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving,
                onClick = {
                    onSave(
                        memory.copy(
                            userNote = userNote.ifBlank { null },
                            aiInterpretation = aiInterpretation.ifBlank { null },
                            peopleText = peopleText.ifBlank { null },
                        )
                    )
                },
            ) { Text("저장") }
        },
        dismissButton = {
            TextButton(enabled = !isSaving, onClick = onDismiss) { Text("취소") }
        },
    )
}

@Composable
private fun MeetingEditDialog(
    meeting: MeetingHistoryItem,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (MeetingHistoryItem) -> Unit,
) {
    var title by remember(meeting) { mutableStateOf(meeting.title) }
    var summary by remember(meeting) { mutableStateOf(meeting.summary.orEmpty()) }
    var markdownSummary by remember(meeting) { mutableStateOf(meeting.markdownSummary.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("회의 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("제목") })
                OutlinedTextField(value = summary, onValueChange = { summary = it }, label = { Text("요약") })
                OutlinedTextField(
                    value = markdownSummary,
                    onValueChange = { markdownSummary = it },
                    label = { Text("회의록 마크다운") },
                    minLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving,
                onClick = {
                    onSave(
                        meeting.copy(
                            title = title,
                            summary = summary.ifBlank { null },
                            markdownSummary = markdownSummary.ifBlank { null },
                        )
                    )
                },
            ) { Text("저장") }
        },
        dismissButton = {
            TextButton(enabled = !isSaving, onClick = onDismiss) { Text("취소") }
        },
    )
}

@Composable
private fun DeleteDialog(
    title: String,
    body: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(enabled = !isSaving, onClick = onConfirm) { Text("삭제") }
        },
        dismissButton = {
            TextButton(enabled = !isSaving, onClick = onDismiss) { Text("취소") }
        },
    )
}

@Composable
private fun MemoryThumbnail(
    filename: String?,
    large: Boolean = false,
) {
    if (filename == null) return
    val repository = remember { MemoryRepository() }
    val bitmap by produceState<Bitmap?>(initialValue = null, filename) {
        value = withContext(Dispatchers.IO) { repository.loadImage(filename) }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Memory image",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (large) 4f / 3f else 16f / 9f)
                .background(Color.Black.copy(alpha = 0.04f)),
            contentScale = ContentScale.Crop,
        )
    }
}

private fun MemoryItem.memoryTypeLabel(): String {
    val normalized = labels.map { it.lowercase() }
    return when {
        normalized.any { it.contains("business_card") } -> "명함"
        normalized.any { it.contains("document") } -> "문서"
        normalized.any { it.contains("person") } -> "사람"
        normalized.any { it.contains("vehicle") } -> "차량"
        normalized.any { it.contains("food") } -> "음식"
        normalized.any { it.contains("place") } -> "장소"
        source == "voice" -> "음성 기억"
        source == "camera" -> "시야 기억"
        else -> "기억"
    }
}

private fun MemoryItem.recallHint(): String {
    val key = userNote
        ?: peopleText
        ?: labels.firstOrNull()
        ?: text.lineSequence().firstOrNull()
        ?: "저장한 내용"
    return key.take(24)
}

private fun MemoryItem.recallExamples(): List<String> {
    val type = memoryTypeLabel()
    val hint = recallHint()
    val label = labels.firstOrNull()?.replace("_", " ")
    return listOfNotNull(
        "\"$hint\" 찾아줘",
        if (type != "기억") "\"지난번 저장한 $type\" 보여줘" else null,
        if (!label.isNullOrBlank()) "\"$label 관련해서 저장한 거 뭐였지?\"" else null,
    ).distinct().take(3)
}
