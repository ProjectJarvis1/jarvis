package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.OfferKind
import com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.ProactiveOffer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.ProactivePlanner
import com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.ProactiveStore
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskListRules
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskListSection
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskRecord
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HomeScreen(viewModel: WearablesViewModel, geminiViewModel: GeminiSessionViewModel, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    if (!com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig.GLASSES) {
        PhoneHome(viewModel::navigateToPhoneMode, geminiViewModel, modifier)
        return
    }
    JarvisHome(false, false, { activity?.let(viewModel::startRegistration) }, viewModel::navigateToPhoneMode, modifier)
}

/** One thing in the home conversation, ordered by when it happened. */
private sealed class FeedEntry(val at: Long, val key: String) {
    class Offer(val offer: ProactiveOffer) : FeedEntry(offer.createdAtMs, "offer-${offer.id}")
    class Work(val task: TaskRecord) : FeedEntry(task.updatedAt, "task-${task.id}-${task.revision}")
    class Exchange(val last: GeminiSessionViewModel.LastExchange) : FeedEntry(last.atMs, "last-${last.atMs}")
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Tasks worth a line on home: anything waiting on the user or still running, and what ended in the last day. */
internal fun homeTasks(tasks: List<TaskRecord>, now: Long): List<TaskRecord> {
    val recent = { t: TaskRecord -> now - t.updatedAt < DAY_MS }
    val open = tasks.filter { TaskListRules.section(it) in setOf(TaskListSection.NEEDS_YOU, TaskListSection.RUNNING) }
    val kept = tasks.filter { it.state == TaskState.COMPLETED && recent(it) }.sortedByDescending { it.updatedAt }.take(3)
    val failed = tasks.filter { it.state == TaskState.FAILED && recent(it) }.sortedByDescending { it.updatedAt }.take(2)
    return open + kept + failed
}

/**
 * Phone build home, shaped like the company site's phone demo: one conversation with Jarvis. What Jarvis
 * offered on its own (meetings, deadlines, mail, the morning briefing), work waiting on the user or still
 * running, and the last exchange sit in one time-ordered thread; the bar at the bottom starts talking.
 */
@Composable
private fun PhoneHome(onStart: () -> Unit, geminiViewModel: GeminiSessionViewModel, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // The conversation needs the microphone (and the camera to show things). Asked here if the first-run guide
    // was skipped or declined; the conversation opens either way, and the permission line says what is off.
    val needed = arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA)
    fun missing() = needed.filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
    // Read from the system each time home comes back, not remembered, so it is right after a trip to settings.
    val screenState by androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val micOff = remember(screenState) {
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { onStart() }
    val start = { if (missing().isEmpty()) onStart() else ask.launch(missing().toTypedArray()) }

    LaunchedEffect(Unit) { ProactiveStore.load(context) }
    val offers by ProactiveStore.offers.collectAsState()
    val tasks by geminiViewModel.taskRecords.collectAsStateWithLifecycle()
    val gemini by geminiViewModel.uiState.collectAsStateWithLifecycle()
    // Re-read the clock every minute so an offer leaves once its event is over.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000L); now = System.currentTimeMillis() } }

    val needsYou = tasks.count { TaskListRules.section(it) == TaskListSection.NEEDS_YOU }
    var showTasks by rememberSaveable { mutableStateOf(false) }
    if (showTasks) TaskListSheet(tasks, remember(geminiViewModel) { TaskListHandlers(
        approvable = geminiViewModel::approvable,
        // A call or text with same-name people waits for a pick on its card, which sits behind this sheet.
        approve = { task -> geminiViewModel.approveFromList(task)
            if (geminiViewModel.uiState.value.pendingContactAction != null) showTasks = false },
        control = { task, action -> geminiViewModel.controlTask(task.id, task.revision, action) },
        edit = { task, args -> geminiViewModel.editTask(task.id, task.revision, args) },
        delete = { task -> geminiViewModel.deleteTask(task.id) },
    ) }, onDismiss = { showTasks = false })

    // Errors from the task list (e.g. a result another task still uses cannot be deleted) show here too.
    LaunchedEffect(gemini.errorMessage) {
        gemini.errorMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            geminiViewModel.clearError()
        }
    }
    val last by geminiViewModel.lastExchange.collectAsStateWithLifecycle()
    val entries = remember(offers, tasks, now, last) {
        (offers.filter { ProactivePlanner.isLive(it, now) }.take(6).map { FeedEntry.Offer(it) } +
            homeTasks(tasks, now).map { FeedEntry.Work(it) } + listOfNotNull(last?.let { FeedEntry.Exchange(it) }))
            .sortedBy { it.at }
    }
    // A call or text approved from the task list waits here for its card, same-name choice included.
    val contact = gemini.pendingContactAction
    val listState = rememberLazyListState()
    val itemCount = 1 + entries.size + (if (contact != null) 1 else 0)
    // Newest at the bottom, like any chat: open at the latest message and follow new ones, also when an old one
    // drops off the top as a new one arrives (the count stays, the last key changes).
    val newest = contact?.id ?: entries.lastOrNull()?.let { "${it.key}@${it.at}" }
    LaunchedEffect(itemCount, newest) {
        // To the very end, not the top of the last item: a long last answer or card ends where the eye lands.
        listState.scrollToItem(itemCount - 1)
        listState.scrollBy(100_000f)
    }

    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().background(scheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            JarvisAvatar(36.dp)
            Column(Modifier.weight(1f)) {
                Text("Jarvis", style = MaterialTheme.typography.titleMedium)
                // The server is only checked inside a conversation, so home says what it knows for sure:
                // what is waiting on the user. The live screen shows the connection once talking starts.
                Text(if (needsYou > 0) "확인할 일 ${needsYou}개가 있어요" else "부르기 전에 먼저 챙겨요",
                    style = MaterialTheme.typography.labelMedium, color = if (needsYou > 0) scheme.primary else scheme.onSurfaceVariant)
            }
            IconButton(onClick = { showTasks = true }, modifier = Modifier.size(48.dp)) {
                BadgedBox(badge = { if (needsYou > 0) Badge { Text("$needsYou") } }) {
                    Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, if (needsYou > 0) "작업 목록, 확인할 일 ${needsYou}개" else "작업 목록")
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "hello") {
                JarvisBubble(if (entries.isEmpty()) "안녕하세요, 자비스예요. 미팅이나 마감이 다가오면 여기서 먼저 말씀드릴게요."
                    else "안녕하세요, 자비스예요. 오늘 챙길 일을 모아 뒀어요.") {
                    Text("아래 말하기 바를 눌러 대화를 연 뒤 이렇게 말해 보세요.", style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant)
                    listOf("이 명함 기억해 줘", "다음 미팅 상대랑 지난번에 무슨 얘기 했지?", "내일 오후 4시 미팅 캘린더에 넣어 줘").forEach {
                        Text("\"$it\"", style = MaterialTheme.typography.bodyMedium, color = Jarvis.extras.onJarvisBubble)
                    }
                }
            }
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is FeedEntry.Offer -> OfferMessage(entry.offer, now) { now = System.currentTimeMillis() }
                    is FeedEntry.Work -> WorkMessage(entry.task, onOpenList = { showTasks = true }, onOpenConversation = start,
                        cardIsThis = geminiViewModel.pendingSave(tasks)?.let { it.id == entry.task.id && it.revision == entry.task.revision } == true)
                    is FeedEntry.Exchange -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChatMeta("${taskWhen(entry.last.atMs)}, 마지막 대화", Icons.Outlined.ChatBubbleOutline)
                        if (entry.last.user.isNotBlank()) UserBubble(entry.last.user)
                        if (entry.last.jarvis.isNotBlank()) JarvisBubble(entry.last.jarvis)
                    }
                }
            }
            contact?.let { action -> item(key = "contact-${action.id}") {
                ContactActionConfirmCard(action, geminiViewModel::confirmPendingContactAction,
                    geminiViewModel::cancelPendingContactAction, geminiViewModel::selectContactChoice, voice = false)
            } }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp, bottom = 10.dp)) {
            if (micOff) Text("마이크가 꺼져 있으면 말로는 대화할 수 없어요. 아래를 누르면 다시 여쭤볼게요.",
                Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            TalkBar(start)
        }
    }
}

/** The bar at the bottom, like a message box. Talking is the only input, so the whole bar starts it. */
@Composable
private fun TalkBar(onStart: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // White with a thin line and a small blue mic, as the site's message box.
    Surface(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        .semantics(mergeDescendants = true) { contentDescription = "대화 시작" },
        shape = RoundedCornerShape(50), color = Jarvis.extras.card, border = BorderStroke(1.dp, scheme.outlineVariant)) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("자비스, 말씀하세요", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
            Box(Modifier.size(38.dp).background(Jarvis.extras.blue, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Mic, null, Modifier.size(20.dp), tint = Jarvis.extras.onBlue)
            }
        }
    }
}

private fun offerIcon(kind: OfferKind) = when (kind) {
    OfferKind.MEETING -> Icons.Outlined.Event
    OfferKind.DEADLINE -> Icons.Outlined.Flag
    OfferKind.MAIL -> Icons.Outlined.MailOutline
    OfferKind.MORNING -> Icons.Outlined.WbSunny
}

/** Something Jarvis offered on its own. Tapping the action opens what it gathered, as the notification does. */
@Composable
private fun OfferMessage(offer: ProactiveOffer, now: Long, onExpired: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ChatMeta("${taskWhen(offer.createdAtMs)}, ${ProactivePlanner.kindLabel(offer.kind)}", offerIcon(offer.kind))
        JarvisBubble(offer.message) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatAction(ProactivePlanner.actionLabel(offer.kind), {
                    if (ProactivePlanner.isLive(offer, System.currentTimeMillis())) ProactiveStore.open(offer) else onExpired()
                })
            }
        }
    }
}

/** A task: waiting on the user (opens the list to approve), still running, or what came of it. */
@Composable
private fun WorkMessage(task: TaskRecord, onOpenList: () -> Unit, onOpenConversation: () -> Unit, cardIsThis: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val section = TaskListRules.section(task)
    val (label, icon, tone) = when {
        section == TaskListSection.NEEDS_YOU -> Triple("확인이 필요해요", Icons.Outlined.PendingActions, scheme.primary)
        section == TaskListSection.RUNNING -> Triple("하고 있어요", Icons.Outlined.HourglassEmpty, scheme.onSurfaceVariant)
        task.state == TaskState.FAILED -> Triple("하지 못했어요", Icons.Outlined.ErrorOutline, scheme.error)
        else -> Triple("끝났어요", Icons.Outlined.TaskAlt, scheme.primary)
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ChatMeta(taskWhen(task.updatedAt), icon, tag = label, tagTone = tone)
        JarvisBubble(task.title) {
            taskLine(task)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            // Record edits are approved only on the card that shows the server's preview, which lives on the
            // conversation screen; the list cannot approve them.
            // The conversation screen shows one card, the newest waiting one; only offer it when it is this task.
            val onCard = cardIsThis && section == TaskListSection.NEEDS_YOU && task.state == TaskState.WAITING_USER &&
                task.tool in setOf("apply_record_change", "undo_record_change")
            Row { ChatAction(when { onCard -> "대화에서 확인하기"; section == TaskListSection.NEEDS_YOU -> "확인하기"; else -> "작업 기록" },
                if (onCard) onOpenConversation else onOpenList, main = section == TaskListSection.NEEDS_YOU) }
        }
    }
}

/** The glasses mark from the company site: two round lenses and a bridge. */
@Composable
fun JarvisMark(modifier: Modifier = Modifier) {
    Icon(painterResource(R.drawable.ic_jarvis_mark), null, modifier, tint = MaterialTheme.colorScheme.onSurface)
}

private enum class GlassesStage { NotLinked, Waiting, Ready }

/**
 * Home is only the way to the glasses. Every request happens in the conversation screen by saying "자비스",
 * so this screen shows where the glasses are and offers one next step. [onGlasses] registers, opens connection
 * help, or starts the conversation depending on the state; [onPhone] uses the phone camera instead.
 */
@Composable
fun JarvisHome(registered: Boolean, deviceReady: Boolean, onGlasses: () -> Unit, onPhone: () -> Unit,
    modifier: Modifier = Modifier, onHelp: (() -> Unit)? = null) {
    val stage = when { deviceReady -> GlassesStage.Ready; registered -> GlassesStage.Waiting; else -> GlassesStage.NotLinked }
    val (heading, body) = when (stage) {
        GlassesStage.Ready -> "글라스가 연결됐어요" to "글라스에 대고 \"자비스\"라고 부르면\n무엇이든 요청할 수 있어요."
        GlassesStage.Waiting -> "글라스를 켜 주세요" to "글라스를 쓰고 전원을 켜면\n바로 대화 화면으로 넘어가요."
        GlassesStage.NotLinked -> "글라스를 연결하세요" to "한 번 연결해 두면 글라스에 대고\n\"자비스\"라고 불러 요청할 수 있어요."
    }
    // Korean wraps by word (phrase), not in the middle of a word.
    val byWord = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            JarvisWordmark(Modifier.weight(1f))
            // While waiting, the main button is the help itself, so the corner link would only repeat it.
            if (onHelp != null && stage != GlassesStage.Waiting) TextButton(onClick = onHelp, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("연결 도움말", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Text(heading, style = MaterialTheme.typography.headlineLarge.copy(lineBreak = byWord))
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium.copy(lineBreak = byWord),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            ProactiveOfferList(Modifier.padding(bottom = 20.dp))
            GlassesPanel(stage)
            Spacer(Modifier.height(24.dp))
            if (stage == GlassesStage.Ready) TryList() else ConnectSteps(stage)
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (stage) {
                GlassesStage.Ready -> JarvisPrimaryButton("대화 시작", onGlasses, Modifier.fillMaxWidth())
                GlassesStage.Waiting -> JarvisSecondaryButton("연결 도움말", onGlasses, Modifier.fillMaxWidth())
                GlassesStage.NotLinked -> JarvisPrimaryButton("글라스 연결하기", onGlasses, Modifier.fillMaxWidth())
            }
            if (stage != GlassesStage.Ready) TextButton(onClick = onPhone, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("휴대폰으로 먼저 시작", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The picture of the glasses: grey while unlinked, blue and turning while waiting, filled with a check once ready. */
@Composable
private fun GlassesPanel(stage: GlassesStage) {
    val scheme = MaterialTheme.colorScheme
    val tinted = stage != GlassesStage.NotLinked
    val ground = if (tinted) Brush.verticalGradient(listOf(scheme.primaryContainer.copy(alpha = .55f), scheme.primaryContainer))
        else Brush.verticalGradient(listOf(scheme.surfaceVariant, scheme.surfaceVariant))
    Box(Modifier.fillMaxWidth().height(184.dp).background(ground, RoundedCornerShape(18.dp))) {
        Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
            when (stage) {
                GlassesStage.NotLinked -> JarvisStatusChip("글라스 연결 안 됨", dot = scheme.outline, container = Jarvis.extras.card,
                    content = scheme.onSurfaceVariant)
                GlassesStage.Waiting -> JarvisStatusChip("글라스 기다리는 중", container = Jarvis.extras.card, content = scheme.primary)
                GlassesStage.Ready -> JarvisStatusChip("글라스 연결됨", container = Jarvis.extras.card, content = scheme.primary)
            }
        }
        Box(Modifier.align(Alignment.Center).size(112.dp), contentAlignment = Alignment.Center) {
            if (stage == GlassesStage.Waiting) WaitingArc(Modifier.fillMaxSize())
            val ready = stage == GlassesStage.Ready
            Surface(shape = CircleShape, modifier = Modifier.size(if (ready) 96.dp else 92.dp),
                color = if (ready) scheme.primary.copy(alpha = .22f) else Jarvis.extras.card,
                border = if (ready) BorderStroke(6.dp, Jarvis.extras.card) else BorderStroke(1.dp, scheme.outlineVariant)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_jarvis_mark), null, Modifier.size(46.dp),
                        tint = when (stage) { GlassesStage.NotLinked -> scheme.outline; GlassesStage.Waiting -> scheme.primary
                            GlassesStage.Ready -> scheme.onSurface })
                }
            }
            if (ready) Surface(shape = CircleShape, color = scheme.primary, border = BorderStroke(2.dp, Jarvis.extras.card),
                modifier = Modifier.align(Alignment.BottomEnd).offset((-6).dp, (-6).dp).size(24.dp)) {
                Icon(Icons.Default.Check, "연결됨", Modifier.padding(4.dp), tint = scheme.onPrimary)
            }
        }
    }
}

/** A quarter arc turning around the glasses while the app waits for them. Still in previews. */
@Composable
private fun WaitingArc(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val angle = if (LocalInspectionMode.current) 0f else {
        val t by rememberInfiniteTransition(label = "wait").animateFloat(0f, 360f,
            infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "arc")
        t
    }
    Canvas(modifier) {
        drawArc(color, startAngle = angle - 90f, sweepAngle = 110f, useCenter = false,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** The three steps to linked glasses. The current one is ringed; finished ones are checked. */
@Composable
private fun ConnectSteps(stage: GlassesStage) {
    val scheme = MaterialTheme.colorScheme
    val steps = listOf("Meta AI 앱에서 글라스 등록", "글라스를 쓰고 전원 켜기", "카메라 권한 확인")
    val current = if (stage == GlassesStage.Waiting) 1 else 0
    Text("연결 순서", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    steps.forEachIndexed { i, step ->
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                i < current -> Surface(shape = CircleShape, color = scheme.primary, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Check, "완료", Modifier.padding(4.dp), tint = scheme.onPrimary)
                }
                i == current && stage == GlassesStage.Waiting -> Surface(shape = CircleShape, color = Jarvis.extras.card,
                    border = BorderStroke(1.5.dp, scheme.primary), modifier = Modifier.size(24.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                    }
                }
                else -> Surface(shape = CircleShape, color = scheme.surfaceVariant, modifier = Modifier.size(24.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                }
            }
            Text(step, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = if (i < current) scheme.onSurfaceVariant else scheme.onSurface)
            if (i == current && stage == GlassesStage.Waiting) Text("기다리는 중", style = MaterialTheme.typography.labelMedium,
                color = scheme.primary)
        }
    }
}

/** Once linked, three things to say, one per kind of request, so the first try works. */
@Composable
private fun TryList() {
    val scheme = MaterialTheme.colorScheme
    Text("이렇게 불러보세요", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    listOf("시야" to "\"자비스, 이게 뭐야?\"", "기억" to "\"이 사람 저장해줘\"", "일정" to "\"내일 오후 4시 미팅 캘린더에 넣어줘\"")
        .forEach { (kind, phrase) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(kind, Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                Text(phrase, style = MaterialTheme.typography.bodyMedium)
            }
        }
}
