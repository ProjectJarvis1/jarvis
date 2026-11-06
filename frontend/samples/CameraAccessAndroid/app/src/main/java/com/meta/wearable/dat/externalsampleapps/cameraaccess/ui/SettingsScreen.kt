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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ServiceConnections
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ServiceConnections.Service
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDebug: (() -> Unit)? = null,
    onDisconnect: (() -> Unit)? = null,
) {
    var geminiAPIKey by remember { mutableStateOf(SettingsManager.geminiAPIKey) }
    var systemPrompt by remember { mutableStateOf(SettingsManager.geminiSystemPrompt) }
    var jarvisApiBase by remember { mutableStateOf(SettingsManager.jarvisApiBase) }
    var jarvisApiKey by remember { mutableStateOf(SettingsManager.jarvisApiKey) }
    var webrtcSignalingURL by remember { mutableStateOf(SettingsManager.webrtcSignalingURL) }
    var videoStreamingEnabled by remember { mutableStateOf(SettingsManager.videoStreamingEnabled) }
    var proactiveNotificationsEnabled by remember { mutableStateOf(SettingsManager.proactiveNotificationsEnabled) }
    var liveConversationEnabled by remember { mutableStateOf(SettingsManager.liveConversationEnabled) }
    var whisperSttEnabled by remember { mutableStateOf(SettingsManager.speechRecognizerProvider == "whisper") }
    var showResetDialog by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }

    fun save() {
        SettingsManager.geminiAPIKey = geminiAPIKey.trim()
        SettingsManager.geminiSystemPrompt = systemPrompt.trim()
        SettingsManager.jarvisApiBase = jarvisApiBase.trim()
        SettingsManager.jarvisApiKey = jarvisApiKey.trim()
        SettingsManager.webrtcSignalingURL = webrtcSignalingURL.trim()
        SettingsManager.videoStreamingEnabled = videoStreamingEnabled
        SettingsManager.proactiveNotificationsEnabled = proactiveNotificationsEnabled
        SettingsManager.liveConversationEnabled = liveConversationEnabled
        SettingsManager.speechRecognizerProvider = if (whisperSttEnabled) "whisper" else "android"
    }

    fun reload() {
        geminiAPIKey = SettingsManager.geminiAPIKey
        systemPrompt = SettingsManager.geminiSystemPrompt
        jarvisApiBase = SettingsManager.jarvisApiBase
        jarvisApiKey = SettingsManager.jarvisApiKey
        webrtcSignalingURL = SettingsManager.webrtcSignalingURL
        videoStreamingEnabled = SettingsManager.videoStreamingEnabled
        proactiveNotificationsEnabled = SettingsManager.proactiveNotificationsEnabled
        liveConversationEnabled = SettingsManager.liveConversationEnabled
        whisperSttEnabled = SettingsManager.speechRecognizerProvider == "whisper"
    }

    // Tab navigation and system Back also persist edits, not only the toolbar Back button.
    LaunchedEffect(geminiAPIKey, systemPrompt, jarvisApiBase, jarvisApiKey, webrtcSignalingURL,
        videoStreamingEnabled, proactiveNotificationsEnabled, liveConversationEnabled, whisperSttEnabled) {
        save()
    }
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("설정", style = MaterialTheme.typography.headlineMedium) },
            navigationIcon = {
                IconButton(onClick = { save(); onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServiceConnectionsSection()

            SectionHeader("대화")
            SettingSwitch("실시간 대화", "말하는 동안 Jarvis가 듣고 답해요. 답하는 중에도 끼어들 수 있어요.",
                liveConversationEnabled) { liveConversationEnabled = it }
            SettingSwitch("연속 영상 전송", "평소에는 꺼 두세요. 필요한 순간의 사진만 보내요.",
                videoStreamingEnabled) { videoStreamingEnabled = it }
            SettingSwitch("서버 알림 음성 안내", "Jarvis 서버에서 온 알림을 글라스 스피커로 읽어 줘요.",
                proactiveNotificationsEnabled) { proactiveNotificationsEnabled = it }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { advancedOpen = !advancedOpen },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("고급 설정", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(if (advancedOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    if (advancedOpen) "고급 설정 접기" else "고급 설정 펼치기")
            }
            if (advancedOpen) {
                MonoTextField(geminiAPIKey, { geminiAPIKey = it }, "Gemini API 키", "API 키 입력", secret = true)
                MonoTextField(jarvisApiBase, { jarvisApiBase = it }, "Jarvis 서버 주소", "https://api.example.com",
                    keyboardType = KeyboardType.Uri)
                MonoTextField(jarvisApiKey, { jarvisApiKey = it }, "Jarvis 서버 API 키", "API 키 입력", secret = true)
                MonoTextField(webrtcSignalingURL, { webrtcSignalingURL = it }, "화면 공유 서버 주소",
                    "비워 두면 Jarvis 서버를 씁니다", keyboardType = KeyboardType.Uri)
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("응답 지침") },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                if (!liveConversationEnabled) {
                    SettingSwitch("기기 안 음성 인식(Whisper)",
                        if (whisperSttEnabled) "기기에서 음성을 인식해요. 속도는 기기에 따라 달라요." else "끄면 휴대폰의 한국어 음성 인식을 써요.",
                        whisperSttEnabled) { whisperSttEnabled = it }
                }
                if (onDisconnect != null) TextButton(onClick = { showDisconnectDialog = true }) { Text("글라스 앱 등록 해제") }
                if (onDebug != null) TextButton(onClick = onDebug) { Text("개발 도구") }
                TextButton(onClick = { showResetDialog = true }) {
                    Text("기본값으로 초기화", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
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

/**
 * Notion, Google Calendar and Slack. "연결" opens the service's sign-in in the browser; the list is read again
 * whenever the user comes back to the app, so a finished sign-in shows up without any extra step.
 */
@Composable
private fun ServiceConnectionsSection() {
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
            .onSuccess { states = it; error = null }
            .onFailure { error = "서버에 연결하지 못했어요. 고급 설정에서 서버 주소와 키를 확인해 주세요." }
    }

    SectionHeader("서비스 연결")
    Text("연결한 서비스는 글라스에서 말로 찾을 수 있어요. 메시지를 보내거나 글을 쓰기 전에는 꼭 확인을 받아요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { reloads++ }) { Text("다시 확인") }
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
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(service.icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(service.label, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state?.connected == true) JarvisDot()
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                state?.connected == true -> TextButton(onClick = { confirmDisconnect = service }, enabled = busy == null,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("해제") }
                else -> OutlinedButton(
                    onClick = {
                        busy = service
                        scope.launch {
                            runCatching { ServiceConnections.linkUrl(service) }
                                .onSuccess { url ->
                                    error = null
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                                .onFailure { error = "${service.label} 연결 주소를 받지 못했어요. 잠시 뒤 다시 눌러 주세요." }
                            busy = null
                        }
                    },
                    enabled = busy == null && state != null && state.ready,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("연결") }
            }
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

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
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
