package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@Composable
fun HomeScreen(viewModel: WearablesViewModel, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    JarvisHome(false, false, { activity?.let(viewModel::startRegistration) }, viewModel::navigateToPhoneMode, modifier)
}

/** The glasses mark from the company site: two round lenses and a bridge. */
@Composable
fun JarvisMark(modifier: Modifier = Modifier) {
    Icon(painterResource(R.drawable.ic_jarvis_mark), null, modifier, tint = MaterialTheme.colorScheme.onSurface)
}

/**
 * Home is only the way to the glasses. Every request happens in the conversation screen by saying "자비스",
 * so this screen shows where the glasses are and offers one next step. [onGlasses] registers, opens connection
 * help, or starts the conversation depending on the state; [onPhone] uses the phone camera instead.
 */
@Composable
fun JarvisHome(registered: Boolean, deviceReady: Boolean, onGlasses: () -> Unit, onPhone: () -> Unit,
    modifier: Modifier = Modifier) {
    val (status, heading, body, action) = when {
        deviceReady -> listOf("글라스 연결됨", "글라스가 연결됐어요",
            "대화를 시작하고 글라스에 대고\n\"자비스\"라고 부르면 무엇이든 요청할 수 있어요.", "대화 시작")
        registered -> listOf("글라스 기다리는 중", "글라스를 켜 주세요",
            "글라스를 쓰고 전원을 켜면\n바로 대화 화면으로 넘어가요.", "연결 도움말")
        else -> listOf("글라스 연결 안 됨", "글라스를 연결하세요",
            "한 번 연결해 두면 글라스에 대고\n\"자비스\"라고 불러 요청할 수 있어요.", "글라스 연결하기")
    }
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            JarvisMark(Modifier.size(28.dp))
            Text("Jarvis", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Spacer(Modifier.height(40.dp))
            Surface(shape = RoundedCornerShape(28.dp), color = if (deviceReady) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(112.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_jarvis_mark), null, Modifier.size(64.dp),
                        tint = if (deviceReady) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JarvisDot(if (deviceReady) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            // Korean wraps by word (phrase), not in the middle of a word.
            val byWord = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)
            Text(heading, style = MaterialTheme.typography.headlineLarge.copy(lineBreak = byWord), textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge.copy(lineBreak = byWord), textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 360.dp))
            Spacer(Modifier.height(40.dp))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = onGlasses, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(action) }
            TextButton(onClick = onPhone, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("휴대폰 카메라로 쓰기", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
