package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import kotlinx.coroutines.launch

/**
 * Shown until the user signs in. Records live on the Jarvis server per account, so nothing else works
 * before this. Debug builds also offer email sign-in for emulators without Google Play services.
 */
@Composable
fun SignInScreen(modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var devOpen by remember { mutableStateOf(false) }
    var devEmail by remember { mutableStateOf("") }
    // Sign-in goes to this address, so a wrong or outdated one must be fixable before signing in.
    var serverOpen by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(SettingsManager.jarvisApiBase) }
    val byWord = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)

    fun run(block: suspend () -> Result<*>) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            // Network and sign-in library errors come in English; show one Korean sentence unless the server already wrote one.
            block().onFailure { error = com.meta.wearable.dat.externalsampleapps.cameraaccess.memory.RecordErrors.text(it, "로그인하지 못했어요", where = "아래 서버 주소") }
            busy = false
        }
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            JarvisWordmark()
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("로그인하고 시작하세요", style = MaterialTheme.typography.headlineLarge.copy(lineBreak = byWord))
            Spacer(Modifier.height(8.dp))
            Text("자비스에 저장한 사람, 회의, 기억은 내 계정에만 보관돼요.\n다른 휴대폰에서 같은 계정으로 로그인하면 그대로 이어져요.",
                style = MaterialTheme.typography.bodyMedium.copy(lineBreak = byWord), color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium.copy(lineBreak = byWord), color = MaterialTheme.colorScheme.error)
            }
            if (serverOpen) {
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(server, { server = it; SettingsManager.jarvisApiBase = it.trim() }, Modifier.fillMaxWidth(),
                    singleLine = true, label = { Text("Jarvis 서버 주소") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
            if (devOpen) {
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(devEmail, { devEmail = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("개발용 이메일") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                Spacer(Modifier.height(8.dp))
                JarvisSecondaryButton(if (busy) "확인하는 중" else "이메일로 로그인", {
                    run { JarvisAccount.signInForDevelopment(devEmail) }
                }, Modifier.fillMaxWidth(), enabled = !busy && "@" in devEmail)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            JarvisPrimaryButton(if (busy) "확인하는 중" else "구글 계정으로 로그인", {
                activity?.let { run { JarvisAccount.signInWithGoogle(it) } }
            }, Modifier.fillMaxWidth(), enabled = !busy)
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                if (!serverOpen) TextButton(onClick = { serverOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("서버 주소", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (JarvisAccount.devSignInAvailable && !devOpen) TextButton(onClick = { devOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("개발용 이메일 로그인", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
