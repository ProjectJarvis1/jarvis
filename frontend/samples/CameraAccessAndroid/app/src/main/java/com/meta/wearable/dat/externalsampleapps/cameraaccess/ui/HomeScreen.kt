package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@Composable
fun HomeScreen(viewModel: WearablesViewModel, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    JarvisHome(false, false, { activity?.let(viewModel::startRegistration) },
        viewModel::navigateToPhoneMode, viewModel::showMemory, viewModel::showSettings, modifier)
}

@Composable
fun JarvisHome(registered: Boolean, deviceReady: Boolean, onGlasses: () -> Unit, onPhone: () -> Unit,
    onRecords: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
        .padding(horizontal = 24.dp).padding(top = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("jarvis", style = MaterialTheme.typography.headlineMedium)
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (deviceReady) Icons.Default.CheckCircle else Icons.Default.Sensors, null,
                        Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (deviceReady) "글라스 연결됨" else if (registered) "글라스 연결 대기" else "휴대폰으로 시작 가능",
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("보고, 말하고,\n기억을 이어가세요", style = MaterialTheme.typography.headlineLarge)
            Text("필요한 순간에 곁에 있는 나의 AI", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF142B55), Color(0xFF2457B6))))) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Color.White.copy(alpha = .12f), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Default.GraphicEq, null, Modifier.padding(16.dp).size(38.dp), tint = Color(0xFFA7C8FF))
                    }
                    Text("JARVIS LIVE", color = Color(0xFFC5D9FF), style = MaterialTheme.typography.labelLarge)
                }
                Text("지금 떠오른 생각,\n편하게 말해보세요", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                Text("카메라를 연결하면 보고 있는 장면도\n함께 이야기할 수 있어요.", color = Color(0xFFD0DEF5), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = if (deviceReady) onGlasses else onPhone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1746A0)),
                    shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp))
                    Text(if (deviceReady) "글라스로 시작하기" else "휴대폰으로 시작하기")
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("나의 일상을 더 가볍게", style = MaterialTheme.typography.titleLarge)
            if (deviceReady) JarvisActionRow(Icons.Default.Smartphone, "휴대폰으로 사용", "글라스 대신 휴대폰 카메라로 대화", onPhone)
            JarvisActionRow(Icons.Default.AutoStories, "기억 꺼내보기", "저장한 장면과 회의를 한곳에서", onRecords)
            JarvisActionRow(Icons.Default.Visibility, if (registered) "글라스 연결" else "글라스 연결하기",
                if (deviceReady) "연결된 글라스로 대화 시작" else "눈앞의 장면을 자비스와 함께", onGlasses)
            JarvisActionRow(Icons.Default.Tune, "내게 맞는 자비스", "음성·카메라·연결 설정", onSettings)
        }
        JarvisCard {
            Text("이렇게 말해보세요", style = MaterialTheme.typography.titleMedium)
            listOf("지금 보고 있는 내용 기억해 줘", "지난 회의에서 결정한 게 뭐였지?", "사진 찍는 동안 이전 기록도 찾아줘").forEach {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("“", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
