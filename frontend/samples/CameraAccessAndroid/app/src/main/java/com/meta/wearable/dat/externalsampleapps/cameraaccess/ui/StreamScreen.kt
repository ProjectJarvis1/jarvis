/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.PendingContactAction
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.PendingContactActionType
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.WakeWordMonitor
import com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingVoiceCommand
import com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingRecordingViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingMode
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCSessionViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    isPhoneMode: Boolean = false,
    isForeground: Boolean = true,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
    geminiViewModel: GeminiSessionViewModel = viewModel(),
    webrtcViewModel: WebRTCSessionViewModel = viewModel(),
    meetingRecordingViewModel: MeetingRecordingViewModel = viewModel(),
) {
    val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
    val geminiUiState by geminiViewModel.uiState.collectAsStateWithLifecycle()
    val webrtcUiState by webrtcViewModel.uiState.collectAsStateWithLifecycle()
    val meetingRecordingUiState by meetingRecordingViewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val currentStartWakeWordSession = rememberUpdatedState<(String) -> Unit>({ rawText ->
        if (!meetingRecordingUiState.isRecording) {
            geminiViewModel.startSession(
                wakeWordInitiated = true,
                initialText = WakeWordMonitor.stripWakeWord(rawText),
            )
        }
    })
    val currentHandleMeetingVoiceCommand = rememberUpdatedState<(MeetingVoiceCommand) -> Unit>({ command ->
        when (command) {
            MeetingVoiceCommand.START -> {
                if (meetingRecordingUiState.isRecording || meetingRecordingUiState.isProcessing) {
                    return@rememberUpdatedState
                }
                if (geminiUiState.isGeminiActive) {
                    geminiViewModel.stopSession()
                }
                meetingRecordingViewModel.startRecording()
                Toast.makeText(context, "회의 시작하겠습니다.", Toast.LENGTH_SHORT).show()
            }
            MeetingVoiceCommand.STOP -> {
                if (!meetingRecordingUiState.isRecording) {
                    return@rememberUpdatedState
                }
                meetingRecordingViewModel.stopAndProcess()
                Toast.makeText(context, "회의 녹음을 종료하고 정리합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    })
    val wakeWordMonitor = remember(context) {
        WakeWordMonitor(
            context = context.applicationContext,
            onWakeWord = { rawText ->
                currentStartWakeWordSession.value(rawText)
            },
            onMeetingCommand = { command, _ ->
                currentHandleMeetingVoiceCommand.value(command)
            },
        )
    }

    fun leaveStream() {
        if (geminiUiState.isGeminiActive) geminiViewModel.stopSession()
        if (webrtcUiState.isActive) webrtcViewModel.stopSession()
        meetingRecordingViewModel.cancelRecording()
        streamViewModel.stopStream()
        wearablesViewModel.navigateToDeviceSelection()
    }

    var confirmLeave by remember { mutableStateOf(false) }
    fun requestLeave() {
        if (meetingRecordingUiState.isRecording) confirmLeave = true else leaveStream()
    }
    BackHandler(enabled = isForeground) { requestLeave() }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false },
        title = { Text("진행 중인 회의가 있어요") },
        text = { Text("회의를 정리한 뒤 종료할 수 있어요. 녹음을 버리고 나가면 이 회의는 저장되지 않아요.") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; meetingRecordingViewModel.stopAndProcess() }) { Text("회의 정리하기") } },
        dismissButton = { Row {
            TextButton(onClick = { confirmLeave = false }) { Text("계속 녹음") }
            TextButton(onClick = { confirmLeave = false; leaveStream() }) { Text("버리고 종료", color = MaterialTheme.colorScheme.error) }
        } })


    // Wire Gemini VM to Stream VM for frame forwarding
    LaunchedEffect(geminiViewModel) {
        streamViewModel.geminiViewModel = geminiViewModel
    }

    // Wire WebRTC VM to Stream VM for frame forwarding
    LaunchedEffect(webrtcViewModel) {
        streamViewModel.webrtcViewModel = webrtcViewModel
    }

    // Start stream or phone camera
    LaunchedEffect(isPhoneMode) {
        if (isPhoneMode) {
            geminiViewModel.streamingMode = StreamingMode.PHONE
            streamViewModel.startPhoneCamera(lifecycleOwner)
        } else {
            geminiViewModel.streamingMode = StreamingMode.GLASSES
            streamViewModel.startStream()
        }
    }

    LaunchedEffect(geminiViewModel) {
        geminiViewModel.voiceCommands.collect { command ->
            currentHandleMeetingVoiceCommand.value(command)
        }
    }

    LaunchedEffect(geminiUiState.isGeminiActive, meetingRecordingUiState.isProcessing) {
        if (!geminiUiState.isGeminiActive && !meetingRecordingUiState.isProcessing) {
            wakeWordMonitor.start()
        } else {
            wakeWordMonitor.stop()
        }
    }

    // Clean up on exit
    DisposableEffect(Unit) {
        onDispose {
            wakeWordMonitor.release()
            if (geminiUiState.isGeminiActive) {
                geminiViewModel.stopSession()
            }
            if (webrtcUiState.isActive) {
                webrtcViewModel.stopSession()
            }
            meetingRecordingViewModel.cancelRecording()
        }
    }

    // Show errors as toasts
    LaunchedEffect(geminiUiState.errorMessage) {
        geminiUiState.errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            geminiViewModel.clearError()
        }
    }
    LaunchedEffect(webrtcUiState.errorMessage) {
        webrtcUiState.errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            webrtcViewModel.clearError()
        }
    }
    LaunchedEffect(webrtcUiState.viewerUrl) {
        if (webrtcUiState.viewerUrl.isNotEmpty()) {
            Toast.makeText(context, "Live link ready. Tap Share.", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(streamUiState.errorMessage) {
        streamUiState.errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(meetingRecordingUiState.errorMessage) {
        meetingRecordingUiState.errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            meetingRecordingViewModel.clearMessage()
        }
    }
    LaunchedEffect(meetingRecordingUiState.result) {
        meetingRecordingUiState.result?.let { result ->
            Toast.makeText(
                context,
                "회의 저장 완료: ${result.summary.take(120)}",
                Toast.LENGTH_LONG,
            ).show()
            meetingRecordingViewModel.clearMessage()
        }
    }

    val statusTitle = when {
        meetingRecordingUiState.isRecording -> "회의 녹음 중"
        meetingRecordingUiState.isProcessing -> "회의 정리 중"
        geminiUiState.toolCallStatus is ToolCallStatus.Executing -> "요청 처리 중"
        geminiUiState.isGeminiActive -> "Jarvis 듣는 중"
        streamUiState.streamSessionState == StreamSessionState.STARTING -> "카메라 연결 중"
        streamUiState.streamSessionState == StreamSessionState.STREAMING -> "Jarvis 준비됨"
        else -> "Jarvis 대기 중"
    }
    val statusBody = when {
        streamUiState.errorMessage != null -> streamUiState.errorMessage!!
        meetingRecordingUiState.isRecording -> "회의 발언을 기록 중입니다. 끝낼 때는 \"회의 녹음 종료\"라고 말하거나 버튼을 누르세요."
        meetingRecordingUiState.isProcessing -> "녹음 내용을 텍스트와 회의록으로 정리하고 있습니다."
        geminiUiState.toolCallStatus is ToolCallStatus.Executing -> geminiUiState.toolCallStatus.displayText
        geminiUiState.isGeminiActive -> "말씀하세요. 현재 시야 질문, 기억 저장, 검색, 전화/문자를 처리할 수 있습니다."
        streamUiState.capturedPhoto != null -> "최근 캡처 이미지를 기준으로 답변했습니다. 다시 질문하면 새로 캡처합니다."
        streamUiState.streamSessionState == StreamSessionState.STREAMING -> "자비스라고 부르거나 AI 버튼을 누르면 질문을 듣습니다."
        else -> "글라스 또는 폰 카메라를 연결하면 Jarvis가 현재 시야를 사용할 수 있습니다."
    }
    val statusChips = listOf(
        StatusChipInfo(
            label = when {
                streamUiState.streamSessionState == StreamSessionState.STREAMING &&
                    streamUiState.streamingMode == StreamingMode.GLASSES -> "글라스 연결됨"
                streamUiState.streamSessionState == StreamSessionState.STREAMING -> "폰 카메라 준비"
                streamUiState.streamSessionState == StreamSessionState.STARTING -> "카메라 연결 중"
                else -> "카메라 대기"
            },
            color = when (streamUiState.streamSessionState) {
                StreamSessionState.STREAMING -> AppColor.Green
                StreamSessionState.STARTING -> Color(0xFFE7A400)
                else -> Color(0xFF8A97A8)
            },
        ),
        StatusChipInfo(
            label = when {
                geminiUiState.isGeminiActive &&
                    geminiUiState.connectionState == GeminiConnectionState.Ready -> "AI 듣는 중"
                geminiUiState.isGeminiActive -> "AI 연결 중"
                else -> "AI 대기"
            },
            color = when {
                geminiUiState.connectionState is GeminiConnectionState.Error -> AppColor.Red
                geminiUiState.isGeminiActive &&
                    geminiUiState.connectionState == GeminiConnectionState.Ready -> AppColor.Green
                geminiUiState.isGeminiActive -> Color(0xFFE7A400)
                else -> Color(0xFF8A97A8)
            },
        ),
        StatusChipInfo(
            label = when (geminiUiState.openClawConnectionState) {
                OpenClawConnectionState.Connected -> "Jarvis 서버 연결됨"
                OpenClawConnectionState.Checking -> "서버 확인 중"
                is OpenClawConnectionState.Unreachable -> "서버 연결 실패"
                OpenClawConnectionState.NotConfigured -> "서버 대기"
            },
            color = when (geminiUiState.openClawConnectionState) {
                OpenClawConnectionState.Connected -> AppColor.Green
                OpenClawConnectionState.Checking -> Color(0xFFE7A400)
                is OpenClawConnectionState.Unreachable -> AppColor.Red
                OpenClawConnectionState.NotConfigured -> Color(0xFF8A97A8)
            },
        ),
        StatusChipInfo(
            label = when {
                meetingRecordingUiState.isRecording -> "회의 녹음 중"
                meetingRecordingUiState.isProcessing -> "회의록 정리 중"
                else -> "회의 대기"
            },
            color = when {
                meetingRecordingUiState.isRecording -> AppColor.Red
                meetingRecordingUiState.isProcessing -> Color(0xFFE7A400)
                else -> Color(0xFF8A97A8)
            },
        ),
    )

    var workspace by rememberSaveable { mutableStateOf(0) }
    Scaffold(modifier = modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = { Column {
                Text("자비스와 함께", style = MaterialTheme.typography.titleLarge)
                Text(if (isPhoneMode) "휴대폰 카메라" else "글라스 카메라", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }, navigationIcon = { IconButton(onClick = ::requestLeave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "홈으로") } },
                actions = { IconButton(onClick = wearablesViewModel::showMemory) { Icon(Icons.Default.CollectionsBookmark, "기록 열기") } })
        }, bottomBar = {
            ControlsRow(
                onStopStream = {
                    requestLeave()
                },
                onToggleAI = {
                    if (geminiUiState.isGeminiActive) {
                        geminiViewModel.stopSession()
                    } else {
                        geminiViewModel.startSession()
                    }
                },
                isAIActive = geminiUiState.isGeminiActive,
                aiEnabled = !meetingRecordingUiState.isRecording &&
                    !meetingRecordingUiState.isProcessing,
                onToggleRecording = {
                    if (meetingRecordingUiState.isRecording) {
                        meetingRecordingViewModel.stopAndProcess()
                    } else {
                        if (geminiUiState.isGeminiActive) {
                            geminiViewModel.stopSession()
                        }
                        meetingRecordingViewModel.startRecording()
                    }
                },
                isRecording = meetingRecordingUiState.isRecording,
                isRecordingProcessing = meetingRecordingUiState.isProcessing,
                onToggleLive = {
                    if (webrtcUiState.isActive) {
                        webrtcViewModel.stopSession()
                    } else {
                        webrtcViewModel.startSession()
                    }
                },
                isLiveActive = webrtcUiState.isActive,
                onShareLive = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Jarvis live view: ${webrtcUiState.viewerUrl}")
                    }
                    context.startActivity(Intent.createChooser(intent, "Share live view"))
                },
                canShareLive = webrtcUiState.viewerUrl.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            )
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = workspace, containerColor = MaterialTheme.colorScheme.background) {
                listOf("대화", "작업").forEachIndexed { index, label ->
                    Tab(selected = workspace == index, onClick = { workspace = index }, text = { Text(label) })
                }
            }
            if (workspace == 1) TaskBoardScreen(geminiViewModel)
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    JarvisCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                Icon(Icons.Default.GraphicEq, null, Modifier.padding(16.dp).size(32.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(if (geminiUiState.isModelSpeaking) "자비스가 답하고 있어요" else statusTitle,
                                    style = MaterialTheme.typography.titleLarge)
                                Text(statusBody, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            statusChips.take(3).forEach { chip ->
                                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(Modifier.size(7.dp).clip(CircleShape).background(chip.color))
                                        Text(chip.label, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
                if (meetingRecordingUiState.isRecording || meetingRecordingUiState.isProcessing) item {
                    MeetingModePanel(meetingRecordingUiState.isRecording, meetingRecordingUiState.isProcessing,
                        meetingRecordingUiState.recordingStartedAtMs, { meetingRecordingViewModel.stopAndProcess() },
                        Modifier.fillMaxWidth().height(340.dp))
                }
                geminiUiState.pendingContactAction?.let { action -> item {
                    ContactActionConfirmCard(action, geminiViewModel::confirmPendingContactAction, geminiViewModel::cancelPendingContactAction)
                } }
                if (geminiUiState.userTranscript.isNotBlank()) item {
                    JarvisCard {
                        Text("나", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                        Text(geminiUiState.userTranscript, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (geminiUiState.aiTranscript.isNotBlank()) item {
                    JarvisCard {
                        Text("자비스", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        Text(geminiUiState.aiTranscript, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                item {
                    val frame = streamUiState.capturedPhoto ?: streamUiState.videoFrame
                    JarvisCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (streamUiState.capturedPhoto != null) "최근 촬영한 장면" else "카메라 미리보기", style = MaterialTheme.typography.titleMedium)
                            TaskBoardButton(geminiViewModel)
                        }
                        if (frame != null) Image(frame.asImageBitmap(), "카메라 장면", Modifier.fillMaxWidth().height(200.dp).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop)
                        else Text("카메라를 준비하고 있어요. 연결되면 여기에 표시돼요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (geminiUiState.taskSummary.isNotBlank()) Text(geminiUiState.taskSummary,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (webrtcUiState.isActive) item { WebRTCOverlay(uiState = webrtcUiState) }
                if (!geminiUiState.isGeminiActive && !meetingRecordingUiState.isRecording) item {
                    Text("아래 대화 시작을 누르거나 ‘자비스’라고 불러보세요.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
                }
            }
        }
    }

    // Share photo dialog
    streamUiState.capturedPhoto?.let { photo ->
        if (streamUiState.isShareDialogVisible) {
            SharePhotoDialog(
                photo = photo,
                onDismiss = { streamViewModel.hideShareDialog() },
                onShare = { bitmap ->
                    streamViewModel.sharePhoto(bitmap)
                    streamViewModel.hideShareDialog()
                },
            )
        }
    }
}

private data class StatusChipInfo(
    val label: String,
    val color: Color,
)

@Composable
private fun ContactActionConfirmCard(
    action: PendingContactAction,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isCall = action.type == PendingContactActionType.CALL
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFFF8FAFC),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = if (isCall) "전화 실행 전 확인" else "문자 전송 전 확인",
                color = Color(0xFF10233F),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "대상: ${action.query}",
                color = Color(0xFF334155),
                fontWeight = FontWeight.Medium,
            )
            if (!isCall) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = action.message.orEmpty(),
                        modifier = Modifier.padding(12.dp),
                        color = Color(0xFF475569),
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text("취소")
                }
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = onConfirm) {
                    Text(if (isCall) "전화 걸기" else "문자 보내기")
                }
            }
        }
    }
}

@Composable
private fun MeetingModePanel(
    isRecording: Boolean,
    isProcessing: Boolean,
    startedAtMs: Long?,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isRecording, startedAtMs) {
        while (isRecording && startedAtMs != null) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val elapsedSeconds = if (isRecording && startedAtMs != null) {
        ((nowMs - startedAtMs) / 1_000L).coerceAtLeast(0L)
    } else {
        0L
    }
    val elapsedText = "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (isProcessing) Color(0xFFFFF7E8) else Color(0xFFFFEEF0),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = if (isProcessing) Icons.Default.TaskAlt else Icons.Default.Mic,
                contentDescription = null,
                tint = if (isProcessing) Color(0xFF9A6A00) else AppColor.Red,
                modifier = Modifier.size(56.dp),
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = if (isProcessing) "회의록 정리 중" else "회의 녹음 중",
                color = Color(0xFF10233F),
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isProcessing) "녹음 내용을 분석하고 있습니다." else elapsedText,
                color = Color(0xFF52627A),
                fontWeight = if (isRecording) FontWeight.Bold else FontWeight.Medium,
                fontSize = if (isRecording) 36.sp else 16.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Surface(
                color = Color.White.copy(alpha = 0.72f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = if (isProcessing) {
                        "잠시 후 회의 목록에서 요약, Action Item, 원문을 확인할 수 있습니다."
                    } else {
                        "회의 중 발화는 AI 명령으로 처리하지 않습니다.\n끝낼 때는 \"회의 녹음 종료\"라고 말하거나 아래 버튼을 누르세요."
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    color = Color(0xFF334155),
                    textAlign = TextAlign.Center,
                )
            }
            if (isRecording) {
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = onStopRecording,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("회의 녹음 종료")
                }
            }
        }
    }
}
