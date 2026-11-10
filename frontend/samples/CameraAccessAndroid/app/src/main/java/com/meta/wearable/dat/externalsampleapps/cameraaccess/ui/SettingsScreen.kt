package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.foundation.background
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ServiceConnections
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.runtime.collectAsState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ServiceConnections.Service
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.WakeWordTrialLog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDebug: (() -> Unit)? = null,
    onDisconnect: (() -> Unit)? = null,
    glassesStatus: String? = null,
) {
    var systemPrompt by remember { mutableStateOf(SettingsManager.geminiSystemPrompt) }
    var jarvisApiBase by remember { mutableStateOf(SettingsManager.jarvisApiBase) }
    var webrtcSignalingURL by remember { mutableStateOf(SettingsManager.webrtcSignalingURL) }
    var videoStreamingEnabled by remember { mutableStateOf(SettingsManager.videoStreamingEnabled) }
    var cardOfferEnabled by remember { mutableStateOf(SettingsManager.businessCardOfferEnabled) }
    var backgroundWakeWordEnabled by remember { mutableStateOf(SettingsManager.backgroundWakeWordEnabled) }
    var meetingBriefingEnabled by remember { mutableStateOf(SettingsManager.meetingBriefingEnabled) }
    var proactiveNotificationsEnabled by remember { mutableStateOf(SettingsManager.proactiveNotificationsEnabled) }
    var liveConversationEnabled by remember { mutableStateOf(SettingsManager.liveConversationEnabled) }
    var whisperSttEnabled by remember { mutableStateOf(SettingsManager.speechRecognizerProvider == "whisper") }
    var showResetDialog by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }

    fun save() {
        SettingsManager.geminiSystemPrompt = systemPrompt.trim()
        SettingsManager.jarvisApiBase = jarvisApiBase.trim()
        SettingsManager.webrtcSignalingURL = webrtcSignalingURL.trim()
        SettingsManager.videoStreamingEnabled = videoStreamingEnabled
        SettingsManager.businessCardOfferEnabled = cardOfferEnabled
        SettingsManager.backgroundWakeWordEnabled = backgroundWakeWordEnabled
        SettingsManager.meetingBriefingEnabled = meetingBriefingEnabled
        SettingsManager.proactiveNotificationsEnabled = proactiveNotificationsEnabled
        SettingsManager.liveConversationEnabled = liveConversationEnabled
        SettingsManager.speechRecognizerProvider = if (whisperSttEnabled) "whisper" else "android"
    }

    fun reload() {
        systemPrompt = SettingsManager.geminiSystemPrompt
        jarvisApiBase = SettingsManager.jarvisApiBase
        webrtcSignalingURL = SettingsManager.webrtcSignalingURL
        videoStreamingEnabled = SettingsManager.videoStreamingEnabled
        cardOfferEnabled = SettingsManager.businessCardOfferEnabled
        backgroundWakeWordEnabled = SettingsManager.backgroundWakeWordEnabled
        meetingBriefingEnabled = SettingsManager.meetingBriefingEnabled
        proactiveNotificationsEnabled = SettingsManager.proactiveNotificationsEnabled
        liveConversationEnabled = SettingsManager.liveConversationEnabled
        whisperSttEnabled = SettingsManager.speechRecognizerProvider == "whisper"
    }

    // Tab navigation and system Back also persist edits, not only the toolbar Back button.
    LaunchedEffect(systemPrompt, jarvisApiBase, webrtcSignalingURL,
        videoStreamingEnabled, cardOfferEnabled, backgroundWakeWordEnabled, meetingBriefingEnabled, proactiveNotificationsEnabled,
        liveConversationEnabled,
        whisperSttEnabled) {
        save()
    }
    var promptOpen by remember { mutableStateOf(false) }
    var serverOk by remember { mutableStateOf<Boolean?>(null) }
    Column(modifier = modifier.fillMaxSize().background(Jarvis.extras.ground)) {
        TopAppBar(
            title = { Text("설정", style = MaterialTheme.typography.headlineMedium) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Jarvis.extras.ground),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccountSection()

            JarvisGroup {
                if (glassesStatus != null) JarvisRow("글라스", leading = { RowIcon(painterResource(com.meta.wearable.dat.externalsampleapps.cameraaccess.R.drawable.ic_jarvis_mark)) },
                    trailing = { StatusText(glassesStatus, glassesStatus == "연결됨") })
                JarvisRow("Jarvis 서버", icon = Icons.Outlined.Dns,
                    trailing = { StatusText(when (serverOk) { true -> "연결됨"; false -> "연결 안 됨"; null -> "확인 중" }, serverOk == true) })
            }

            ServiceConnectionsSection(onServer = { serverOk = it })

            JarvisGroup {
                SettingSwitch("실시간 대화", "말하는 동안 Jarvis가 듣고 답해요. 답하는 중에도 끼어들 수 있어요.",
                    liveConversationEnabled) { liveConversationEnabled = it }
                if (!liveConversationEnabled) SettingSwitch("기기 안 음성 인식(Whisper)",
                    if (whisperSttEnabled) "기기에서 음성을 인식해요. 속도는 기기에 따라 달라요." else "끄면 휴대폰의 한국어 음성 인식을 써요.",
                    whisperSttEnabled) { whisperSttEnabled = it }
                SettingSwitch("화면 꺼져도 '자비스' 듣기", "휴대폰을 주머니에 넣어도 불러서 대화할 수 있어요. 배터리를 더 써요.",
                    backgroundWakeWordEnabled) { backgroundWakeWordEnabled = it }
                WakeWordTrialRows()
                SettingSwitch("미팅 전 브리핑",
                    "캘린더 일정 5~10분 전에 상대 정보와 지난 회의 내용을 글라스로 알려 줘요. 대화 화면이 열려 있어야 하고 캘린더 권한이 필요해요.",
                    meetingBriefingEnabled) { meetingBriefingEnabled = it }
                SettingSwitch("연속 영상 전송", "평소에는 꺼 두세요. 필요한 순간의 사진만 보내요.",
                    videoStreamingEnabled) { videoStreamingEnabled = it }
                SettingSwitch("명함 보이면 저장 제안", "명함이 보이면 Jarvis가 저장할지 먼저 물어요. 대답하기 전에는 저장하지 않아요.",
                    cardOfferEnabled) { cardOfferEnabled = it }
                SettingSwitch("서버 알림 음성 안내", "Jarvis 서버에서 온 알림을 글라스 스피커로 읽어 줘요.",
                    proactiveNotificationsEnabled) { proactiveNotificationsEnabled = it }
            }

            JarvisGroup {
                JarvisRow("응답 지침", onClick = { promptOpen = !promptOpen }, trailing = { Chevron(promptOpen) })
                if (promptOpen) OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    modifier = Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 14.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                JarvisRow("고급 설정", onClick = { advancedOpen = !advancedOpen }, trailing = { Chevron(advancedOpen) })
                if (advancedOpen) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MonoTextField(jarvisApiBase, { jarvisApiBase = it }, "Jarvis 서버 주소", "https://api.example.com",
                        keyboardType = KeyboardType.Uri)
                    MonoTextField(webrtcSignalingURL, { webrtcSignalingURL = it }, "화면 공유 서버 주소",
                        "비워 두면 Jarvis 서버를 씁니다", keyboardType = KeyboardType.Uri)
                    if (onDisconnect != null) TextButton(onClick = { showDisconnectDialog = true }) { Text("글라스 앱 등록 해제") }
                    if (onDebug != null) TextButton(onClick = onDebug) { Text("개발 도구") }
                    TextButton(onClick = { showResetDialog = true }) {
                        Text("기본값으로 초기화", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showDisconnectDialog && onDisconnect != null) AlertDialog(
        onDismissRequest = { showDisconnectDialog = false }, title = { Text("글라스 앱 등록을 해제할까요?") },
        text = { Text("다시 글라스를 사용하려면 앱을 등록해야 해요. 저장된 기억은 유지돼요.") },
        confirmButton = { TextButton(onClick = { showDisconnectDialog = false; onDisconnect() }) { Text("등록 해제") } },
        dismissButton = { TextButton(onClick = { showDisconnectDialog = false }) { Text("유지하기") } })

    if (showResetDialog) AlertDialog(
        onDismissRequest = { showResetDialog = false },
        title = { Text("설정을 초기화할까요?") },
        text = { Text("앱에 들어 있는 기본 설정으로 되돌려요. 서비스 연결은 그대로 남아요.") },
        confirmButton = {
            TextButton(onClick = { SettingsManager.resetAll(); reload(); showResetDialog = false }) {
                Text("초기화", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("취소") } },
    )
}

/** Who is signed in, sign-out, and account deletion (Google Play requires deletion inside the app). */
@Composable
private fun AccountSection() {
    val scope = rememberCoroutineScope()
    val user by JarvisAccount.user.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    JarvisGroup {
        JarvisRow(user?.name?.ifBlank { null } ?: "내 계정", subtitle = user?.email?.ifBlank { null }, icon = Icons.Outlined.AccountCircle)
        JarvisRow("로그아웃", onClick = { scope.launch { JarvisAccount.signOut() } })
        JarvisRow("계정 삭제", subtitle = error, onClick = { confirmDelete = true },
            trailing = { Text("모든 기록 삭제", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) })
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { if (!deleting) confirmDelete = false },
        title = { Text("계정을 삭제할까요?") },
        text = { Text("서버에 저장한 사람, 회의, 기억, 사진, 녹음과 연결한 서비스가 모두 지워져요. 되돌릴 수 없어요.") },
        confirmButton = {
            TextButton(enabled = !deleting, onClick = {
                deleting = true
                scope.launch {
                    JarvisAccount.deleteAccount().onFailure { error = it.message }
                    deleting = false; confirmDelete = false
                }
            }) { Text(if (deleting) "지우는 중" else "삭제", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(enabled = !deleting, onClick = { confirmDelete = false }) { Text("유지하기") } },
    )
}

/**
 * Notion, Google Calendar and Slack. "연결" opens the service's sign-in in the browser; the list is read again
 * whenever the user comes back to the app, so a finished sign-in shows up without any extra step.
 */
@Composable
private fun ServiceConnectionsSection(onServer: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var states by remember { mutableStateOf<Map<Service, ServiceConnections.State>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<Service?>(null) }
    var confirmDisconnect by remember { mutableStateOf<Service?>(null) }
    var reloads by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) reloads++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(reloads) {
        runCatching { ServiceConnections.status() }
            .onSuccess { states = it; error = null; onServer(true) }
            .onFailure { onServer(false); error = "서버에 연결하지 못했어요. 고급 설정의 서버 주소를 확인하거나 다시 로그인해 주세요." }
    }

    JarvisGroup {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("서비스 연결", style = MaterialTheme.typography.titleSmall)
            Text("연결한 서비스는 글라스에서 말로 찾을 수 있어요. 메시지를 보내거나 글을 쓰기 전에는 꼭 확인을 받아요.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { reloads++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 확인") }
            }
        }
        Service.entries.forEach { service ->
            val state = states?.get(service)
            val status = when {
                busy == service -> "처리 중…"
                state == null -> if (error == null) "확인 중…" else "알 수 없음"
                state.connected -> "연결됨"
                !state.ready -> "서버에 ${service.label} 앱 키를 넣어야 연결할 수 있어요"
                else -> "연결 안 됨"
            }
            JarvisRow(service.label, subtitle = if (state?.connected == true) null else status,
                leading = { RowIcon(painterResource(service.icon)) },
                trailing = {
                    if (state?.connected == true) Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusText("연결됨", true)
                        TextButton(onClick = { confirmDisconnect = service }, enabled = busy == null,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("해제", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else Surface(
                        onClick = {
                            busy = service
                            scope.launch {
                                runCatching { ServiceConnections.linkUrl(service) }
                                    .onSuccess { url ->
                                        error = null
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                    .onFailure { error = (it as? ServiceConnections.ServiceRefused)?.message
                                        ?: "${service.label} 연결 주소를 받지 못했어요. 잠시 뒤 다시 눌러 주세요." }
                                busy = null
                            }
                        },
                        enabled = busy == null && state != null && state.ready,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (busy == null && state?.ready == true) 1f else .4f),
                        modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 6.dp),
                    ) {
                        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                            Text("연결", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = if (busy == null && state?.ready == true) 1f else .5f))
                        }
                    }
                })
        }
    }

    confirmDisconnect?.let { service ->
        AlertDialog(
            onDismissRequest = { confirmDisconnect = null },
            title = { Text("${service.label} 연결을 해제할까요?") },
            text = { Text("Jarvis가 더 이상 ${service.label}에 접근하지 않아요. 언제든 다시 연결할 수 있어요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = null
                    busy = service
                    scope.launch {
                        runCatching { ServiceConnections.disconnect(service) }
                            .onFailure { error = "${service.label} 연결을 해제하지 못했어요. 다시 시도해 주세요." }
                        busy = null
                        reloads++
                    }
                }) { Text("해제") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = null }) { Text("취소") } },
        )
    }
}

/**
 * The wake word trial record, folded under its switch: how often "자비스" was heard with the screen
 * off, listening errors, and battery since the record started, so a pocket test needs no logcat.
 */
@Composable
private fun WakeWordTrialRows() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    JarvisRow("호출 시험 기록", subtitle = "화면이 꺼진 상태에서 몇 번 알아들었는지 봐요",
        onClick = { open = !open; refresh++ }, trailing = { Chevron(open) })
    if (!open) return
    val lines = remember(refresh) {
        WakeWordTrialLog.read(context).summary(System.currentTimeMillis(), WakeWordTrialLog.battery(context))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text("갤럭시는 설정의 배터리에서 이 앱을 '제한 없음'으로 바꿔야 주머니에서도 계속 들어요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        TextButton(onClick = {
            runCatching {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }) { Text("앱 배터리 설정 열기") }
        TextButton(onClick = { WakeWordTrialLog.reset(context); refresh++ }) { Text("기록 지우기") }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Grey tile holding a service or device mark, the leading item of a settings row. */
@Composable
private fun RowIcon(painter: androidx.compose.ui.graphics.painter.Painter) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(36.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painter, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun StatusText(text: String, good: Boolean) {
    Text(text, style = MaterialTheme.typography.labelMedium,
        color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Chevron(open: Boolean) {
    Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.AutoMirrored.Filled.KeyboardArrowRight,
        if (open) "접기" else "펼치기", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
}

@Composable
private fun MonoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}
