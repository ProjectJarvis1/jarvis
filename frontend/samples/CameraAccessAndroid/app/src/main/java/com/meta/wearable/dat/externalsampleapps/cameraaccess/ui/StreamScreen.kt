/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.lifecycle.compose.currentStateAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BookmarkBorder
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.PendingContactAction
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.PendingContactActionType
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.WakeWordMonitor
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.WakeWordTrialLog
import com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingFollowups
import com.meta.wearable.dat.externalsampleapps.cameraaccess.notify.JarvisNotifications
import com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.BriefingSpeaker
import com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.MeetingBriefer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingService
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingVoiceCommand
import com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingRecordingViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingMode
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCSessionViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
    var showTaskList by rememberSaveable { mutableStateOf(false) }
    if (showTaskList) TaskListSheet(taskRecords, remember(geminiViewModel) { TaskListHandlers(
        approvable = geminiViewModel::approvable,
        approve = geminiViewModel::approveFromList,
        control = { task, action -> geminiViewModel.controlTask(task.id, task.revision, action) },
        edit = { task, args -> geminiViewModel.editTask(task.id, task.revision, args) },
        delete = { task -> geminiViewModel.deleteTask(task.id) },
    ) }, onDismiss = { showTaskList = false })
    val webrtcUiState by webrtcViewModel.uiState.collectAsStateWithLifecycle()
    val meetingRecordingUiState by meetingRecordingViewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    // Wake word callbacks also fire with the screen off, when the lifecycle-collected states above are
    // frozen, so they read the view models' live values.
    val currentStartWakeWordSession = rememberUpdatedState<(String) -> Unit>({ rawText ->
        if (!meetingRecordingViewModel.uiState.value.isRecording) {
            geminiViewModel.startSession(
                wakeWordInitiated = true,
                initialText = WakeWordMonitor.stripWakeWord(rawText),
            )
        }
    })
    val currentHandleMeetingVoiceCommand = rememberUpdatedState<(MeetingVoiceCommand) -> Unit>({ command ->
        when (command) {
            MeetingVoiceCommand.START -> {
                val meeting = meetingRecordingViewModel.uiState.value
                if (meeting.isRecording || meeting.isProcessing) {
                    return@rememberUpdatedState
                }
                if (geminiViewModel.uiState.value.isGeminiActive) {
                    geminiViewModel.stopSession()
                }
                meetingRecordingViewModel.startRecording()
                Toast.makeText(context, "회의 시작하겠습니다.", Toast.LENGTH_SHORT).show()
            }
            MeetingVoiceCommand.STOP -> {
                if (!meetingRecordingViewModel.uiState.value.isRecording) {
                    return@rememberUpdatedState
                }
                meetingRecordingViewModel.stopAndProcess()
                Toast.makeText(context, "회의 녹음을 종료하고 정리합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    })
    val speaker = remember(context) { BriefingSpeaker(context.applicationContext) }
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

    LaunchedEffect(geminiViewModel, meetingRecordingViewModel) {
        geminiViewModel.cardOfferPaused = { meetingRecordingViewModel.uiState.value.isRecording }
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

    // Collected directly, not through the lifecycle-aware state: with the screen locked the wake word
    // must still pause for a conversation and resume when it ends.
    LaunchedEffect(geminiViewModel, meetingRecordingViewModel) {
        // A briefing read aloud by the phone pauses it too: its own words ("회의 시작" in a title) must not
        // be taken as a voice command. A conversation or a recording cuts the briefing short.
        // Recording keeps it on: "회의 녹음 종료" is heard through it.
        combine(
            geminiViewModel.uiState.map { it.isGeminiActive },
            meetingRecordingViewModel.uiState.map { it.isRecording to it.isProcessing },
            speaker.speaking,
        ) { gemini, (recording, processing), speaking ->
            if (gemini || recording) speaker.stop()
            !gemini && !processing && !speaking
        }
            .distinctUntilChanged()
            .collect { idle -> if (idle) wakeWordMonitor.start() else wakeWordMonitor.stop() }
    }

    // Hold the foreground service (and, when enabled, the microphone) for as long as this screen is
    // open, so "자비스" still works with the screen off. Re-applied whenever the screen comes back
    // from Settings: the microphone type can only be granted while the app is visible.
    // Applied on every real return to the app (ON_RESUME, e.g. after granting the microphone in system
    // settings) and the moment the switch changes in Settings; both happen with the app on screen.
    // With the switch off the screen holds nothing, so phone mode keeps no service or wake lock.
    fun holdListening() {
        val app = context.applicationContext
        if (!SettingsManager.backgroundWakeWordEnabled) {
            StreamingService.stop(app, StreamingService.REASON_LISTEN)
            return
        }
        WakeWordTrialLog.update(app) { it.started(System.currentTimeMillis(), WakeWordTrialLog.battery(app)) }
        if (!StreamingService.start(app, StreamingService.REASON_LISTEN, microphone = true)) {
            WakeWordTrialLog.update(app) { it.copy(serviceNote = "백그라운드 대기 서비스를 시작하지 못했어요.") }
        }
    }
    DisposableEffect(lifecycleOwner) {
        holdListening()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) holdListening() }
        lifecycleOwner.lifecycle.addObserver(observer)
        val unobserve = SettingsManager.observe("backgroundWakeWordEnabled") { holdListening() }
        onDispose {
            unobserve()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Meeting briefing: every minute, brief meetings 5 to 12 minutes away. Collected outside the lifecycle
    // so it runs with the screen off; Jarvis says it in a conversation, otherwise the phone's own
    // voice does over the glasses, and a notification keeps the full text either way.
    val calendarPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    // Asked when the screen opens, and again the moment the switch is turned on in Settings.
    DisposableEffect(Unit) {
        fun askCalendar() {
            if (SettingsManager.meetingBriefingEnabled && !MeetingBriefer.hasCalendarPermission(context)) {
                calendarPermission.launch(android.Manifest.permission.READ_CALENDAR)
            }
        }
        askCalendar()
        val unobserve = SettingsManager.observe("meetingBriefingEnabled") {
            // Off also silences a briefing being read or queued.
            if (!SettingsManager.meetingBriefingEnabled) speaker.stop()
            askCalendar()
        }
        onDispose { unobserve() }
    }
    LaunchedEffect(Unit) {
        val app = context.applicationContext
        val owner = com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount.userId
        // Briefings whose spoken delivery is still waiting for a quiet moment; not started twice.
        val speaking = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        while (true) {
            if (SettingsManager.meetingBriefingEnabled) {
                val due = withContext(Dispatchers.IO) { runCatching { MeetingBriefer.due(app) }.getOrDefault(emptyList()) }
                due.forEach { (meeting, brief) ->
                    // Searching takes time: the switch may be off now, or the meeting already started.
                    if (!SettingsManager.meetingBriefingEnabled || meeting.beginMs - System.currentTimeMillis() < 60_000L) return@forEach
                    if (!speaking.add(meeting.key)) return@forEach
                    // Counted as delivered once something reached the user: the notification, Jarvis's
                    // voice, or the phone's. If none did, the next poll tries again while it is still due.
                    if (JarvisNotifications.post(app, meeting.key.hashCode(), "곧 시작: ${meeting.title}", brief.detail, owner)) {
                        MeetingBriefer.markDelivered(app, meeting.key)
                    }
                    geminiViewModel.announce(
                        "[앱 알림] 곧 시작하는 일정 브리핑입니다. 아래 내용을 그대로 짧게 전해 주세요. " +
                            "여기에 없는 내용은 덧붙이지 말고, 날짜는 기록된 날짜 그대로 말하세요.\n" + brief.spoken,
                        stillWanted = { SettingsManager.meetingBriefingEnabled },
                        onSpoken = { MeetingBriefer.markDelivered(app, meeting.key) },
                        onFinished = { speaking.remove(meeting.key) },
                    ) {
                        // No conversation to speak through: the phone reads it, unless a conversation is
                        // running but busy, where a second voice would talk over it.
                        // During a meeting recording only the notification is left: a voice would interrupt
                        // the meeting and end up in its recording.
                        if (SettingsManager.meetingBriefingEnabled && !geminiViewModel.uiState.value.isGeminiActive &&
                            !meetingRecordingViewModel.uiState.value.isRecording) {
                            if (speaker.speak(brief.spoken)) MeetingBriefer.markDelivered(app, meeting.key)
                        }
                    }
                }
            }
            delay(60_000L)
        }
    }

    // Clean up on exit
    DisposableEffect(Unit) {
        onDispose {
            speaker.release()
            StreamingService.stop(context.applicationContext, StreamingService.REASON_LISTEN)
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
    // Collected directly: a meeting ended by voice with the phone in a pocket must still hand over its
    // follow-ups. Jarvis says them when a conversation is on; otherwise a phone notification does.
    LaunchedEffect(meetingRecordingViewModel) {
        val owner = com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount.userId
        meetingRecordingViewModel.results.collect { result ->
            val app = context.applicationContext
            Toast.makeText(app, "회의 저장 완료: ${result.summary.take(120)}", Toast.LENGTH_LONG).show()
            if (result.followups.isEmpty()) return@collect
            val start = MeetingFollowups.parseStart(result.startedAt)
            val lines = result.followups.take(8).mapIndexed { i, item ->
                // Full date for the model, so it never proposes the wrong year.
                val due = start?.let { s -> MeetingFollowups.dueDate(item, s)?.let { "${it.year}년 ${it.monthValue}월 ${it.dayOfMonth}일까지" } }
                    ?: item.dueText.takeIf { it.isNotBlank() }
                val extra = listOfNotNull(item.owner.takeIf { it.isNotBlank() }?.let { "담당 $it" }, due).joinToString(", ")
                "${i + 1}. ${item.task}" + if (extra.isNotEmpty()) " ($extra)" else ""
            }
            geminiViewModel.announce(
                "[앱 알림] 방금 회의 정리가 끝났고 후속 할 일이 ${result.followups.size}개 있습니다.\n" +
                    lines.joinToString("\n") +
                    "\n사용자에게 할 일을 짧게 알려 주고, 일정에 넣거나 후속 문자를 보낼지 물어보세요. " +
                    "사용자가 분명히 승인하기 전에는 일정 등록이나 문자 발송을 하지 마세요. " +
                    "기한이 정해지지 않은 항목은 날짜를 먼저 물어보세요. 전체 내용은 기억 탭의 회의 기록에 있다고 알려 주세요.",
            ) {
                JarvisNotifications.post(app, result.meetingId.hashCode(),
                    "회의 후속 할 일 ${result.followups.size}개",
                    lines.joinToString("\n") + "\n기억 탭의 회의 기록에서 일정에 넣거나 후속 문자를 보낼 수 있어요.", owner)
            }
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
    // Checked again each time the screen comes back (e.g. from the phone's settings), so the line follows the switch.
    val screenState by androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val micAllowed = remember(screenState) {
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val listening = when {
        !micAllowed -> "마이크가 꺼져 있어요. 휴대폰 설정에서 마이크를 허용해 주세요."
        meetingRecordingUiState.isRecording -> "회의를 기록하고 있어요"
        geminiUiState.isModelSpeaking -> "자비스가 답하고 있어요"
        geminiUiState.isGeminiActive -> "듣고 있어요"
        else -> "버튼을 누르거나 ‘자비스’라고 불러보세요"
    }
    val meetingActive = meetingRecordingUiState.isRecording || meetingRecordingUiState.isProcessing
    var confirmDiscard by remember { mutableStateOf(false) }
    // A recording ended another way (voice "회의 녹음 종료") is being saved; the discard question no longer applies.
    LaunchedEffect(meetingRecordingUiState.isRecording) { if (!meetingRecordingUiState.isRecording) confirmDiscard = false }
    if (confirmDiscard && meetingRecordingUiState.isRecording) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text("녹음을 버릴까요?") },
        text = { Text("지금까지 녹음한 회의는 저장되지 않아요.") },
        confirmButton = { TextButton(onClick = {
            confirmDiscard = false
            if (meetingRecordingViewModel.uiState.value.isRecording) meetingRecordingViewModel.cancelRecording()
        }) {
            Text("버리기", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("계속 녹음") } })
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        val streaming = streamUiState.streamSessionState == StreamState.STREAMING
        val serverChip = statusChips[2]
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f)) {
                if (meetingActive) JarvisStatusChip(statusTitle, dot = MaterialTheme.colorScheme.error,
                    container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
                else JarvisStatusChip(statusChips[0].label,
                    dot = if (streaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    container = if (streaming) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    content = if (streaming) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(serverChip.label, style = MaterialTheme.typography.labelMedium,
                color = if (serverChip.color == AppColor.Red) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = wearablesViewModel::showMemory, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.BookmarkBorder, "기억 열기", Modifier.size(22.dp))
            }
        }
        if (meetingActive) {
            MeetingModePanel(meetingRecordingUiState.isRecording, meetingRecordingUiState.isProcessing,
                meetingRecordingUiState.recordingStartedAtMs, { meetingRecordingViewModel.stopAndProcess() },
                onDiscard = { confirmDiscard = true },
                modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding())
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                val frame = streamUiState.capturedPhoto ?: streamUiState.videoFrame
                Box(Modifier.fillMaxWidth().height(232.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF111318))) {
                    if (frame != null) Image(frame.asImageBitmap(), if (streamUiState.capturedPhoto != null) "최근 촬영한 장면" else "카메라 미리보기",
                        Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Text("카메라를 준비하고 있어요", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = .7f))
                    Surface(Modifier.align(Alignment.TopStart).padding(10.dp), shape = RoundedCornerShape(50),
                        color = Color.Black.copy(alpha = .55f)) {
                        Text(if (isPhoneMode) "휴대폰 카메라" else "글라스 시야", Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium, color = Color.White)
                    }
                }
            }
            if (problems.isNotEmpty()) item {
                Text(problems.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            // Approval cards sit right under the camera so they are on screen the moment Jarvis asks.
            geminiUiState.pendingContactAction?.let { action -> item {
                ContactActionConfirmCard(action, geminiViewModel::confirmPendingContactAction, geminiViewModel::cancelPendingContactAction)
            } }
            geminiViewModel.pendingSave(taskRecords)?.let { save -> item(key = "save-${save.id}-${save.revision}") {
                SaveConfirmCard(save, onSave = { geminiViewModel.controlTask(save.id, save.revision, "approve") },
                    onCancel = { geminiViewModel.controlTask(save.id, save.revision, "cancel") },
                    loadChange = geminiViewModel::readChange,
                    onPreviewShown = { shown -> geminiViewModel.setRecordPreviewShown(save.id, save.revision, shown) },
                    visible = isForeground)
            } }
            if (geminiUiState.userTranscript.isNotBlank()) item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("나", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(geminiUiState.userTranscript, style = MaterialTheme.typography.headlineSmall)
                }
            }
            if (geminiUiState.aiTranscript.isNotBlank()) item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("자비스", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(geminiUiState.aiTranscript, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp))
                }
            }
            // Words spoken over Jarvis: outlined (not filled) so they read as "not asked yet".
            if (geminiUiState.queuedTranscript.isNotBlank()) item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.background,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(geminiUiState.queuedTranscript.trim(), style = MaterialTheme.typography.bodyLarge)
                        Text("지금 답이 끝나면 이어서 들을게요", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val working = (geminiUiState.toolCallStatus as? ToolCallStatus.Executing)?.displayText
                ?: (com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskListRules.summary(taskRecords) +
                    if (geminiViewModel.taskStorageError != null) " · 기록 저장 오류" else "")
            if (working.isNotBlank()) item {
                // Tapping the count line opens every kept task, to approve, fix, resume or delete it.
                Surface(onClick = { showTaskList = true }, shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .6f)) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        JarvisDot()
                        Text(working, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        if (taskRecords.isNotEmpty()) Text("목록", style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
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
            onStartRecording = {
                if (geminiUiState.isGeminiActive) {
                    geminiViewModel.stopSession()
                }
                meetingRecordingViewModel.startRecording()
            },
            onEnd = { requestLeave() },
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

/**
 * The approval card, shaped like the Figma sheet: optional initial avatar, the question, the facts being approved
 * in a grey block, how to answer by voice, then 취소 and the action. Only facts that exist are shown.
 */
@Composable
private fun ConfirmSheet(headline: String, actionLabel: String, onAction: () -> Unit, onCancel: () -> Unit,
    initial: String? = null, facts: List<Pair<String, String>> = emptyList(), body: String? = null,
    lines: List<String> = emptyList(),
    hint: String? = null, error: String? = null, actionEnabled: Boolean = true) {
    Surface(Modifier.fillMaxWidth(), color = Jarvis.extras.card, shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (initial != null) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(initial, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text(headline, style = MaterialTheme.typography.titleLarge)
            if (facts.isNotEmpty() || body != null || lines.isNotEmpty()) Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    facts.forEach { (label, value) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.End, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                    body?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 6,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    // Changes being approved are shown in full, one per line: nothing approved is hidden.
                    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            hint?.let {
                Text(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JarvisSecondaryButton("취소", onCancel, Modifier.weight(1f))
                JarvisPrimaryButton(actionLabel, onAction, Modifier.weight(2f), enabled = actionEnabled)
            }
        }
    }
}

@Composable
private fun ContactActionConfirmCard(
    action: PendingContactAction,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val isCall = action.type == PendingContactActionType.CALL
    val label = if (isCall) "전화 걸기" else "문자 앱 열기"
    ConfirmSheet(
        headline = if (isCall) "${action.query}에게 전화할까요?" else "${action.query}에게 보낼 문자를 문자 앱에 띄울까요?",
        actionLabel = label, onAction = onConfirm, onCancel = onCancel,
        initial = action.query.trim().take(1).ifBlank { null },
        // The whole message is shown: it is exactly what will be sent.
        lines = if (isCall) emptyList() else listOfNotNull(action.message?.takeIf { it.isNotBlank() }),
        hint = "\"확인\"이라고 말하거나 $label 버튼을 누르세요.",
    )
}

/** Shown the moment Jarvis waits on a save, so the user can tap or just say "확인". */
@Composable
private fun SaveConfirmCard(task: com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskRecord, onSave: () -> Unit, onCancel: () -> Unit,
    loadChange: suspend (String) -> org.json.JSONObject, onPreviewShown: (Boolean) -> Unit = {}, visible: Boolean = true) {
    val args = remember(task.argsJson) { runCatching { org.json.JSONObject(task.argsJson) }.getOrNull() }
    if (task.tool == "apply_record_change" || task.tool == "undo_record_change") {
        RecordChangeCard(task.tool == "undo_record_change", args?.optString("change_id").orEmpty(), onSave, onCancel, loadChange,
            onPreviewShown, visible)
        return
    }
    fun arg(key: String) = args?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }
    if (task.tool == "draft_mail_reply") {
        // Nothing is sent from here: the mail app opens with the draft, and the user sends it there.
        val draft = com.meta.wearable.dat.externalsampleapps.cameraaccess.phone.MailDraft.from(arg("to"), arg("subject"), arg("body")).getOrNull()
        ConfirmSheet(
            headline = "이 답장을 메일 앱에 띄울까요?",
            actionLabel = "지메일에서 열기", onAction = onSave, onCancel = onCancel,
            facts = listOfNotNull(draft?.to?.let { "받는 사람" to it }, draft?.subject?.let { "제목" to it }),
            body = draft?.body,
            error = if (draft == null) "받는 사람 주소나 내용이 비어 있어요. 다시 말씀해 주세요." else null,
            hint = "보내기는 메일 앱에서 직접 누르세요. \"확인\"이라고 말하거나 지메일에서 열기를 누르세요.",
            actionEnabled = draft != null,
        )
        return
    }
    val name = arg("name").orEmpty()
    val face = args?.optBoolean("include_face") == true
    val external = com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ExternalTools.isExternal(task.tool)
    // Outside-service writes show what will be sent, so the user approves the actual message, not a title.
    val externalPreview = if (external) com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ExternalTools
        .preview(task.tool, task.argsJson).split("\n", limit = 2) else emptyList()
    val person = task.tool == "save_person" && name.isNotBlank()
    // A person is approved field by field; other saves show what will be kept, so a misheard note is caught.
    val facts = if (person) listOfNotNull(arg("org")?.let { "회사" to it }, arg("role")?.let { "직책" to it },
        arg("notes_summary")?.let { "메모" to it }, if (face) "얼굴" to "함께 저장" else null) else emptyList()
    val saveContent = if (external || person) null else listOf("user_note", "text", "summary", "ai_interpretation")
        .firstNotNullOfOrNull { arg(it) }
    val action = if (external) "확인" else "저장"
    ConfirmSheet(
        headline = when {
            external -> externalPreview.first()
            person && face -> "$name 님을 얼굴과 함께 저장할까요?"
            person -> "$name 님을 저장할까요?"
            else -> "${task.title}, 저장할까요?"
        },
        actionLabel = if (person && face) "동의받고 저장" else action, onAction = onSave, onCancel = onCancel,
        initial = if (person) name.take(1) else null,
        facts = facts,
        // What goes to an outside service is shown in full; a note to keep may be clipped.
        // A face is saved only with the person's consent (D37), so the card asks the user to confirm it.
        lines = listOfNotNull(externalPreview.getOrNull(1),
            if (person && face) "상대방이 얼굴 저장에 동의했나요? 동의를 받은 경우에만 저장해 주세요." else null),
        body = saveContent,
        hint = if (person && face) "동의를 받았다면 \"확인\"이라고 말하거나 동의받고 저장 버튼을 누르세요."
            else "\"확인\"이라고 말하거나 $action 버튼을 누르세요.",
    )
}

/**
 * A record change is approved on what it actually does: the card loads the server's preview and names the
 * operation ("기억을 삭제할까요?" / 삭제), and the approve button stays off until the preview is on screen.
 */
@Composable
private fun RecordChangeCard(undo: Boolean, changeId: String, onApprove: () -> Unit, onCancel: () -> Unit,
    loadChange: suspend (String) -> org.json.JSONObject, onPreviewShown: (Boolean) -> Unit = {}, visible: Boolean = true) {
    var change by remember(changeId) { mutableStateOf<org.json.JSONObject?>(null) }
    var failed by remember(changeId) { mutableStateOf<String?>(null) }
    LaunchedEffect(changeId) {
        runCatching { loadChange(changeId) }.onSuccess { change = it }
            .onFailure { failed = it.message ?: "변경 내용을 불러오지 못했어요." }
    }
    // A spoken "확인" counts only while the loaded preview is actually in front of the user: not while loading,
    // after a failure, or while another screen (기억, 설정) covers the conversation (D70).
    // The app sent to the background (screen off, another app) hides the card too.
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    val appState by lifecycle.currentStateAsState()
    val inFront = appState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    LaunchedEffect(changeId, change, visible, inFront) { onPreviewShown(change != null && visible && inFront) }
    // Off screen, the preview is no longer in front of the user: a spoken "확인" stops counting (D70).
    androidx.compose.runtime.DisposableEffect(changeId) { onDispose { onPreviewShown(false) } }
    val loaded = change
    val noun = when (loaded?.optString("kind")) { "person" -> "사람 정보를"; "meeting" -> "회의 기록을"; else -> "기억을" }
    val delete = loaded?.optJSONObject("after_data")?.optString("action") == "delete"
    val (headline, action) = when {
        loaded == null -> "변경 내용을 확인하고 있어요" to "확인"
        undo -> "방금 바꾼 $noun 되돌릴까요?" to "되돌리기"
        delete -> "이 $noun 삭제할까요?" to "삭제"
        else -> "이 $noun 이렇게 바꿀까요?" to "변경"
    }
    val lines = remember(loaded) {
        // Undo goes the other way: what is there now (after) turns back into what was there (before).
        val applied = loaded?.optJSONObject("before_data")?.optJSONObject("values") ?: org.json.JSONObject()
        val changed = loaded?.optJSONObject("after_data")?.optJSONObject("values") ?: org.json.JSONObject()
        val before = if (undo) changed else applied
        val after = if (undo) applied else changed
        when {
            loaded == null -> emptyList()
            delete -> listOfNotNull(listOf("text", "name", "title", "summary").firstNotNullOfOrNull { k ->
                before.optString(k).takeIf { it.isNotBlank() && it != "null" } })
            else -> after.keys().asSequence().filter { before.opt(it)?.toString() != after.opt(it)?.toString() }
                .map { "${before.opt(it)?.toString()?.takeIf { v -> v != "null" } ?: "(없음)"} → ${after.opt(it)}" }.toList()
        }
    }
    ConfirmSheet(
        headline = headline, actionLabel = action, onAction = onApprove, onCancel = onCancel,
        lines = lines,
        error = failed,
        hint = if (loaded != null) "\"확인\"이라고 말하거나 $action 버튼을 누르세요." else null,
        actionEnabled = loaded != null,
    )
}

/**
 * Meeting recording takes the whole screen, as in the Figma: elapsed time large, a quiet level line, the rule
 * that speech is not taken as commands, and one black button to end. Discarding asks first.
 */
@Composable
private fun MeetingModePanel(
    isRecording: Boolean,
    isProcessing: Boolean,
    startedAtMs: Long?,
    onStopRecording: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isRecording, startedAtMs) {
        while (isRecording && startedAtMs != null) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val elapsedSeconds = if (isRecording && startedAtMs != null) ((nowMs - startedAtMs) / 1_000L).coerceAtLeast(0L) else 0L
    val elapsedText = if (elapsedSeconds >= 3600) "%d:%02d:%02d".format(elapsedSeconds / 3600, elapsedSeconds / 60 % 60, elapsedSeconds % 60)
        else "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
    val startedText = remember(startedAtMs) {
        startedAtMs?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        }
    }
    Column(modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // The body scrolls; the stop and discard buttons stay pinned below it, reachable at any font size.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (isProcessing) {
                    CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(20.dp))
                    Text("회의록 정리 중", style = MaterialTheme.typography.headlineMedium)
                } else {
                    Text(elapsedText, style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"))
                    startedText?.let {
                        Text("회의 $it 시작", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(28.dp))
                    LevelLine()
                }
                Spacer(Modifier.height(28.dp))
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(if (isProcessing) "녹음 내용을 회의록으로 정리하고 있어요. 끝나면 기억 탭의 회의에서 요약, Action Item, 원문을 볼 수 있어요."
                        else "회의 중 발화는 AI 명령으로 처리하지 않아요. 끝낼 때는 \"회의 녹음 종료\"라고 말하거나 아래 버튼을 누르세요.",
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (isRecording) {
            Spacer(Modifier.height(12.dp))
            JarvisStopButton("회의 녹음 종료", onStopRecording, Modifier.fillMaxWidth(), leading = Icons.Default.Stop)
            TextButton(onClick = onDiscard, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("버리고 종료", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A still voice-level line: tall in the middle, tapering out. It marks "recording", it is not a live meter. */
@Composable
private fun LevelLine() {
    val heights = listOf(6, 10, 8, 14, 12, 20, 16, 28, 22, 36, 30, 44, 34, 26, 38, 22, 30, 16, 20, 12, 14, 8, 10, 6)
    Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        heights.forEachIndexed { i, h ->
            val center = 1f - kotlin.math.abs(i - heights.size / 2f) / (heights.size / 2f)
            Box(Modifier.width(3.dp).height(h.dp).background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = .25f + .75f * center), RoundedCornerShape(2.dp)))
        }
    }
}
