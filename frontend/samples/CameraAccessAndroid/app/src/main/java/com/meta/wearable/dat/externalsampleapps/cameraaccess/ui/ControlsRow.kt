package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ControlsRow(onStopStream: () -> Unit, onToggleAI: () -> Unit, isAIActive: Boolean, aiEnabled: Boolean,
    onToggleRecording: () -> Unit, isRecording: Boolean, isRecordingProcessing: Boolean,
    onToggleLive: () -> Unit, isLiveActive: Boolean, onShareLive: () -> Unit, canShareLive: Boolean,
    modifier: Modifier = Modifier) {
    var more by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onToggleAI, enabled = aiEnabled, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Icon(if (isAIActive) Icons.Default.Stop else Icons.Default.Mic, null)
                Spacer(Modifier.width(8.dp))
                Text(if (isAIActive) "대화 마치기" else "대화 시작")
            }
            FilledTonalIconButton(onClick = onToggleRecording, enabled = !isRecordingProcessing, modifier = Modifier.size(56.dp)) {
                Icon(if (isRecording) Icons.Default.StopCircle else Icons.Default.MicNone,
                    if (isRecording) "회의 녹음 종료" else if (isRecordingProcessing) "회의 정리 중" else "회의 녹음 시작")
            }
            Box {
                IconButton(onClick = { more = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.MoreHoriz, "세션 메뉴") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(if (isLiveActive) "실시간 공유 중단" else "실시간 공유 시작") },
                        onClick = { more = false; onToggleLive() }, leadingIcon = { Icon(Icons.Default.Videocam, null) })
                    if (canShareLive) DropdownMenuItem(text = { Text("시청 링크 공유") },
                        onClick = { more = false; onShareLive() }, leadingIcon = { Icon(Icons.Default.Share, null) })
                    DropdownMenuItem(text = { Text("카메라 연결 종료") }, onClick = { more = false; onStopStream() },
                        leadingIcon = { Icon(Icons.Default.Close, null) })
                }
            }
        }
    }
}
