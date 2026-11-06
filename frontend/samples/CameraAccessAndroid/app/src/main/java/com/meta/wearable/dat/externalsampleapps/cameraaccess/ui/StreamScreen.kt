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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.filled.Close
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
import com.meta.wearable.dat.camera.types.StreamState
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
    val taskRecords by geminiViewModel.taskRecords.collectAsStateWithLifecycle()
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
        streamUiState.streamSessionState == StreamState.STARTING -> "카메라 연결 중"
        streamUiState.streamSessionState == StreamState.STREAMING -> "Jarvis 준비됨"
        else -> "Jarvis 대기 중"
    }
    val statusBody = when {
        streamUiState.errorMessage != null -> streamUiState.errorMessage!!
        meetingRecordingUiState.isRecording -> "회의 발언을 기록 중입니다. 끝낼 때는 \"회의 녹음 종료\"라고 말하거나 버튼을 누르세요."
        meetingRecordingUiState.isProcessing -> "녹음 내용을 텍스트와 회의록으로 정리하고 있습니다."
        geminiUiState.toolCallStatus is ToolCallStatus.Executing -> geminiUiState.toolCallStatus.displayText
        geminiUiState.isGeminiActive -> "말씀하세요. 현재 시야 질문, 기억 저장, 검색, 전화/문자를 처리할 수 있습니다."
        streamUiState.capturedPhoto != null -> "최근 캡처 이미지를 기준으로 답변했습니다. 다시 질문하면 새로 캡처합니다."
        streamUiState.streamSessionState == StreamState.STREAMING -> "자비스라고 부르거나 AI 버튼을 누르면 질문을 듣습니다."
        else -> "글라스 또는 폰 카메라를 연결하면 Jarvis가 현재 시야를 사용할 수 있습니다."
    }
    val statusChips = listOf(
        StatusChipInfo(
            label = when {
                streamUiState.streamSessionState == StreamState.STREAMING &&
                    streamUiState.streamingMode == StreamingMode.GLASSES -> "글라스 연결됨"
                streamUiState.streamSessionState == StreamState.STREAMING -> "폰 카메라 준비"
                streamUiState.streamSessionState == StreamState.STARTING -> "카메라 연결 중"
                else -> "카메라 대기"
            },
            color = when (streamUiState.streamSessionState) {
                StreamState.STREAMING -> AppColor.Green
                StreamState.STARTING -> Color(0xFFE7A400)
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

    val problems = statusChips.take(3).filter { it.color == AppColor.Red }.map { it.label }
    val listening = when {
        meetingRecordingUiState.isRecording -> "회의를 기록하고 있어요"
        geminiUiState.isModelSpeaking -> "자비스가 답하고 있어요"
        geminiUiState.isGeminiActive -> "듣고 있어요"
        else -> "버튼을 누르거나 ‘자비스’라고 불러보세요"
    }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JarvisDot(if (meetingRecordingUiState.isRecording) AppColor.Red else MaterialTheme.colorScheme.onSurface)
                Text(statusTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("· " + if (isPhoneMode) "휴대폰 카메라" else "글라스 카메라", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = ::requestLeave, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "대화 종료") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                val frame = streamUiState.capturedPhoto ?: streamUiState.videoFrame
                Surface(Modifier.fillMaxWidth().height(250.dp), shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    if (frame != null) Image(frame.asImageBitmap(), if (streamUiState.capturedPhoto != null) "최근 촬영한 장면" else "카메라 미리보기",
                        Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Box(contentAlignment = Alignment.Center) {
                        Text("카메라를 준비하고 있어요", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (problems.isNotEmpty()) item {
                Text(problems.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (meetingRecordingUiState.isRecording || meetingRecordingUiState.isProcessing) item {
                MeetingModePanel(meetingRecordingUiState.isRecording, meetingRecordingUiState.isProcessing,
                    meetingRecordingUiState.recordingStartedAtMs, { meetingRecordingViewModel.stopAndProcess() },
                    Modifier.fillMaxWidth())
            }
            geminiUiState.pendingContactAction?.let { action -> item {
                ContactActionConfirmCard(action, geminiViewModel::confirmPendingContactAction, geminiViewModel::cancelPendingContactAction)
            } }
            geminiViewModel.pendingSave(taskRecords)?.let { save -> item(key = "save-${save.id}-${save.revision}") {
                SaveConfirmCard(save, onSave = { geminiViewModel.controlTask(save.id, save.revision, "approve") },
                    onCancel = { geminiViewModel.controlTask(save.id, save.revision, "cancel") })
            } }
            if (geminiUiState.userTranscript.isNotBlank()) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Surface(Modifier.fillMaxWidth(.8f).wrapContentWidth(Alignment.End), shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(geminiUiState.userTranscript, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (geminiUiState.aiTranscript.isNotBlank()) item {
                Text(geminiUiState.aiTranscript, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp))
            }
            // Words spoken over Jarvis: outlined (not filled) so they read as "not asked yet".
            if (geminiUiState.queuedTranscript.isNotBlank()) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Surface(Modifier.fillMaxWidth(.8f).wrapContentWidth(Alignment.End), shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.background,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(geminiUiState.queuedTranscript.trim(), style = MaterialTheme.typography.bodyLarge)
                            Text("지금 답이 끝나면 이어서 들을게요", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            val working = (geminiUiState.toolCallStatus as? ToolCallStatus.Executing)?.displayText ?: geminiUiState.taskSummary
            if (working.isNotBlank()) item {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        JarvisDot()
                        Text(working, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (webrtcUiState.isActive) item { WebRTCOverlay(uiState = webrtcUiState) }
        }
        LiveControls(
            listening = listening,
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
            onRecords = wearablesViewModel::showMemory,
            onStopStream = { requestLeave() },
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        )
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
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = if (isCall) "전화 실행 전 확인" else "문자 전송 전 확인",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "대상: ${action.query}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            if (!isCall) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = action.message.orEmpty(),
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurface,
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

/** Shown the moment Jarvis waits on a save, so the user can tap or just say "확인". */
@Composable
private fun SaveConfirmCard(task: com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskRecord, onSave: () -> Unit, onCancel: () -> Unit) {
    val args = remember(task.argsJson) { runCatching { org.json.JSONObject(task.argsJson) }.getOrNull() }
    val name = args?.optString("name").orEmpty()
    val face = args?.optBoolean("include_face") == true
    val external = com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ExternalTools.isExternal(task.tool)
    // Outside-service writes show what will be sent, so the user approves the actual message, not a title.
    val externalPreview = if (external) com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ExternalTools
        .preview(task.tool, task.argsJson).split("\n", limit = 2) else emptyList()
    val headline = when {
        external -> externalPreview.first()
        task.tool == "save_person" && name.isNotBlank() -> if (face) "$name 님을 얼굴과 함께 저장할까요?" else "$name 님을 저장할까요?"
        else -> "${task.title}, 저장할까요?"
    }
    val action = if (external) "확인" else "저장"
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(headline, style = MaterialTheme.typography.titleMedium)
            externalPreview.getOrNull(1)?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 6,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Text(if (external) "\"확인\"이라고 말하거나 확인을 누르세요." else "\"확인\"이라고 말하거나 저장을 누르세요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("취소") }
                Spacer(Modifier.size(8.dp))
                Button(onClick = onSave) { Text(action) }
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
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = if (isProcessing) Icons.Default.TaskAlt else Icons.Default.Mic,
                contentDescription = null,
                tint = if (isProcessing) MaterialTheme.colorScheme.onSurface else AppColor.Red,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = if (isProcessing) "회의록 정리 중" else "회의 녹음 중",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isProcessing) "녹음 내용을 분석하고 있습니다." else elapsedText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (isRecording) FontWeight.Bold else FontWeight.Medium,
                fontSize = if (isRecording) 36.sp else 16.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = if (isProcessing) {
                        "잠시 후 회의 목록에서 요약, Action Item, 원문을 확인할 수 있습니다."
                    } else {
                        "회의 중 발화는 AI 명령으로 처리하지 않습니다.\n끝낼 때는 \"회의 녹음 종료\"라고 말하거나 아래 버튼을 누르세요."
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (isRecording) {
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = onStopRecording,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text("회의 녹음 종료")
                }
            }
        }
    }
}
