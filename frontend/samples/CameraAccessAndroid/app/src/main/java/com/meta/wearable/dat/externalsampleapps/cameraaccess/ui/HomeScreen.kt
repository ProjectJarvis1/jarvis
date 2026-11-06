package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@Composable
fun HomeScreen(viewModel: WearablesViewModel, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    JarvisHome(false, false, { activity?.let(viewModel::startRegistration) },
        viewModel::navigateToPhoneMode, viewModel::showMemory, modifier)
}

/**
 * Home: connection pill, headline, spoken examples and an ask bar. Settings live in the bottom bar.
 * [onGlasses] connects or starts the glasses; [onPhone] starts with the phone camera.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JarvisHome(registered: Boolean, deviceReady: Boolean, onGlasses: () -> Unit, onPhone: () -> Unit,
    onRecords: () -> Unit, modifier: Modifier = Modifier) {
    val onStart = if (deviceReady) onGlasses else onPhone
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 24.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Jarvis", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(48.dp))
            Surface(onClick = if (deviceReady) onStart else onGlasses, shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.heightIn(min = 36.dp)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JarvisDot(if (deviceReady) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                    Text(when { deviceReady -> "글라스 연결됨"; registered -> "글라스 연결 대기 · 도움말"; else -> "글라스 연결하기" },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("보고, 말하고,\n기억을 이어가세요", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(36.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("“이거 기억해줘”" to onStart, "“지난번 미팅에서 어떤 얘기를 했지?”" to onRecords,
                    "“아까 찍은 사진 다시 설명해줘”" to onRecords).forEach { (label, action) ->
                    Surface(onClick = action, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.heightIn(min = 44.dp)) {
                        Box(Modifier.padding(horizontal = 18.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (deviceReady) TextButton(onClick = onPhone, modifier = Modifier.padding(top = 12.dp).heightIn(min = 44.dp)) {
                Text("휴대폰 카메라로 사용", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(onClick = onStart, shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.heightIn(min = 64.dp).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Jarvis에게 물어보기", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = onStart, modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.PhotoCamera, "카메라로 시작") }
                    FilledIconButton(onClick = onStart, modifier = Modifier.size(48.dp), shape = CircleShape) { Icon(Icons.Default.Mic, "대화 시작") }
                }
            }
        }
    }
}
