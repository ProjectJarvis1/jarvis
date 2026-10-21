package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Live conversation controls from the design canvas: a voice level, what Jarvis is doing, and three round buttons —
 * session menu (meeting, live share, records, end), talk, and the task board.
 */
@Composable
fun LiveControls(listening: String, onToggleAI: () -> Unit, isAIActive: Boolean, aiEnabled: Boolean,
    onToggleRecording: () -> Unit, isRecording: Boolean, isRecordingProcessing: Boolean,
    onToggleLive: () -> Unit, isLiveActive: Boolean, onShareLive: () -> Unit, canShareLive: Boolean,
    onRecords: () -> Unit, onStopStream: () -> Unit, tasks: @Composable () -> Unit, modifier: Modifier = Modifier) {
    var more by remember { mutableStateOf(false) }
    Column(modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (isAIActive) Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(10, 20, 30, 16, 26, 12).forEach { h ->
                Surface(Modifier.width(4.dp).height(h.dp), shape = RoundedCornerShape(2.dp), color = MaterialTheme.colorScheme.onSurface) {}
            }
        }
        Text(listening, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Box {
                OutlinedIconButton(onClick = { more = true }, modifier = Modifier.size(56.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) { Icon(Icons.Default.MoreHoriz, "세션 메뉴") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(if (isRecording) "회의 녹음 종료" else if (isRecordingProcessing) "회의 정리 중" else "회의 녹음 시작") },
                        enabled = !isRecordingProcessing, onClick = { more = false; onToggleRecording() },
                        leadingIcon = { Icon(if (isRecording) Icons.Default.StopCircle else Icons.Default.MicNone, null) })
                    DropdownMenuItem(text = { Text(if (isLiveActive) "실시간 공유 중단" else "실시간 공유 시작") },
                        onClick = { more = false; onToggleLive() }, leadingIcon = { Icon(Icons.Default.Videocam, null) })
                    if (canShareLive) DropdownMenuItem(text = { Text("시청 링크 공유") },
                        onClick = { more = false; onShareLive() }, leadingIcon = { Icon(Icons.Default.Share, null) })
                    DropdownMenuItem(text = { Text("기억 열기") }, onClick = { more = false; onRecords() },
                        leadingIcon = { Icon(Icons.Default.Search, null) })
                    DropdownMenuItem(text = { Text("카메라 연결 종료") }, onClick = { more = false; onStopStream() },
                        leadingIcon = { Icon(Icons.Default.Close, null) })
                }
            }
            FilledIconButton(onClick = onToggleAI, enabled = aiEnabled, modifier = Modifier.size(80.dp), shape = CircleShape) {
                Icon(if (isAIActive) Icons.Default.Stop else Icons.Default.Mic, if (isAIActive) "대화 마치기" else "말하기",
                    Modifier.size(32.dp))
            }
            tasks()
        }
    }
}
