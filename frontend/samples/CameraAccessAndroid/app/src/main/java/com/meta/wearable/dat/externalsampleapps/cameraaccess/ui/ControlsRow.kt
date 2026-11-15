package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Live conversation controls, as in the Figma: what Jarvis is doing, then three labelled round buttons.
 * 회의 녹음 starts a recording (the recording screen has its own stop), the middle one talks or stops listening,
 * and 대화 마치기 leaves the conversation screen.
 */
@Composable
fun LiveControls(listening: String, onToggleAI: () -> Unit, isAIActive: Boolean, aiEnabled: Boolean,
    onStartRecording: () -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(listening, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceEvenly) {
            SideControl("회의 녹음", Icons.Default.FiberManualRecord, MaterialTheme.colorScheme.error, onStartRecording, aiEnabled)
            TalkControl(isAIActive, aiEnabled, onToggleAI)
            SideControl("대화 마치기", Icons.Default.Close, MaterialTheme.colorScheme.onSurface, onEnd, true)
        }
    }
}

@Composable
private fun SideControl(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit, enabled: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(52.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, label, Modifier.size(if (icon == Icons.Default.FiberManualRecord) 16.dp else 22.dp),
                    tint = if (enabled) tint else tint.copy(alpha = .35f))
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The main button: the same solid blue mic as the home talk bar, with a pale halo while listening. */
@Composable
private fun TalkControl(active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val x = Jarvis.extras
    val scheme = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            if (active) Box(Modifier.fillMaxSize().clip(CircleShape).background(scheme.primaryContainer))
            Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = Color.Transparent, modifier = Modifier.size(68.dp)) {
                Box(Modifier.fillMaxSize().background(
                    if (enabled) x.userBubble else scheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(if (active) Icons.Default.GraphicEq else Icons.Default.Mic, if (active) "듣기 멈추기" else "말하기",
                        Modifier.size(30.dp), tint = if (enabled) x.onUserBubble else scheme.onSurfaceVariant)
                }
            }
        }
        Text(if (active) "듣는 중" else "말하기", style = MaterialTheme.typography.labelMedium,
            color = if (active) scheme.primary else scheme.onSurfaceVariant)
    }
}
