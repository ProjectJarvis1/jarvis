package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.ForwardToInbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp

/** One permission the onboarding asks for, with the one place Jarvis uses it. No Android calls, so it is testable. */
data class OnboardingPermission(val key: String, val title: String, val use: String, val permissions: List<String>)

object OnboardingPermissions {
    /** Notifications are a runtime permission only from Android 13 (API 33). */
    fun list(sdk: Int): List<OnboardingPermission> = buildList {
        add(OnboardingPermission("mic", "마이크", "자비스라고 부르고 말로 대화할 때 써요.", listOf(Manifest.permission.RECORD_AUDIO)))
        add(OnboardingPermission("camera", "카메라", "명함이나 눈앞의 장면을 보여 줄 때 써요.", listOf(Manifest.permission.CAMERA)))
        add(OnboardingPermission("contacts", "연락처와 전화", "이름으로 전화를 걸거나 문자를 준비할 때 써요.",
            listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.CALL_PHONE)))
        add(OnboardingPermission("calendar", "캘린더", "미팅 전 브리핑과 마감 알림을 위해 일정을 읽어요.", listOf(Manifest.permission.READ_CALENDAR)))
        if (sdk >= 33) add(OnboardingPermission("notify", "알림", "먼저 챙긴 일과 아침 브리핑을 알림으로 보내요.",
            listOf(Manifest.permission.POST_NOTIFICATIONS)))
    }
}

private enum class Ask { NOT_YET, GRANTED, DENIED }

private fun granted(context: Context, p: OnboardingPermission) =
    p.permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/**
 * Shown once, right after the first sign-in: what Jarvis does, why each permission is asked (one at a time,
 * each skippable), and what to say first. Nothing here blocks the app; a "no" only turns that one thing off.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val last = 2
    val byWord = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)
    Column(modifier.fillMaxSize().background(Jarvis.extras.ground).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            JarvisWordmark(Modifier.weight(1f))
            if (step < last) TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("건너뛰기", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        StepBar(step, last + 1, Modifier.padding(horizontal = 20.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            when (step) {
                0 -> WhatJarvisDoes(byWord)
                1 -> Permissions(byWord)
                else -> FirstWords(byWord)
            }
            Spacer(Modifier.height(24.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // On the grey ground the grey secondary button would vanish, so "이전" sits on the white card color.
            if (step > 0) Surface(onClick = { step-- }, shape = RoundedCornerShape(12.dp), color = Jarvis.extras.card,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Box(Modifier.heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
                    Text("이전", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            JarvisPrimaryButton(if (step == last) "시작하기" else "다음", { if (step == last) onDone() else step++ }, Modifier.weight(2f))
        }
    }
}

/** Three short bars: the screens are a sequence, so where you are is worth showing. */
@Composable
private fun StepBar(step: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().semantics { contentDescription = "${count}단계 중 ${step + 1}단계" },
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(Modifier.weight(1f).height(4.dp).background(
                if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun Heading(title: String, body: String, byWord: LineBreak) {
    Text(title, style = MaterialTheme.typography.headlineLarge.copy(lineBreak = byWord))
    Spacer(Modifier.height(8.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium.copy(lineBreak = byWord), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun WhatJarvisDoes(byWord: LineBreak) {
    Heading("부르기 전에 먼저 챙기는 비서예요", "휴대폰만 있으면 바로 시작할 수 있어요.", byWord)
    JarvisGroup {
        Feature(Icons.Outlined.Bookmarks, "기억해 둬요", "만난 사람, 받은 명함, 회의에서 한 말을 기억했다가 물으면 찾아 줘요.")
        Feature(Icons.Outlined.EventAvailable, "먼저 알려 줘요", "미팅과 마감 전에 지난 기록을 모아 먼저 말을 걸어요.")
        Feature(Icons.Outlined.ForwardToInbox, "전화와 문자를 준비해요", "보낼 내용을 채워 두면 확인한 뒤 직접 보내요.")
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    JarvisRow(title, subtitle = body, icon = icon, iconTint = MaterialTheme.colorScheme.primary,
        iconContainer = MaterialTheme.colorScheme.primaryContainer)
}

@Composable
private fun Permissions(byWord: LineBreak) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val items = remember { OnboardingPermissions.list(android.os.Build.VERSION.SDK_INT) }
    val state = remember { mutableStateMapOf<String, Ask>() }
    LaunchedEffect(Unit) { items.forEach { if (granted(context, it)) state[it.key] = Ask.GRANTED } }
    var asking by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val item = items.firstOrNull { it.key == asking } ?: return@rememberLauncherForActivityResult
        state[item.key] = if (granted(context, item)) Ask.GRANTED else Ask.DENIED
        if (item.key == "calendar") {
            // "먼저 알려 주기" asks for the calendar once at start; this was that one ask.
            context.getSharedPreferences("proactive_permission", Context.MODE_PRIVATE).edit().putBoolean("asked", true).apply()
        }
        asking = null
    }
    Heading("필요한 권한만 하나씩 여쭤볼게요", "허용하지 않아도 앱은 쓸 수 있어요. 그 기능만 쉬고, 나중에 휴대폰 설정에서 바꿀 수 있어요.", byWord)
    JarvisGroup {
        items.forEach { item ->
            val icon = when (item.key) {
                "mic" -> Icons.Outlined.Mic
                "camera" -> Icons.Outlined.PhotoCamera
                "contacts" -> Icons.Outlined.Call
                "calendar" -> Icons.Outlined.CalendarMonth
                else -> Icons.Outlined.NotificationsNone
            }
            JarvisRow(item.title, subtitle = item.use, icon = icon, trailing = {
                when (state[item.key] ?: Ask.NOT_YET) {
                    Ask.GRANTED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("허용함", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Ask.DENIED -> Text("나중에", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Ask.NOT_YET -> Surface(onClick = {
                        if (activity != null && asking == null) { asking = item.key; launcher.launch(item.permissions.toTypedArray()) }
                    }, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.heightIn(min = 40.dp)) {
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text("허용", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            })
        }
    }
}

@Composable
private fun FirstWords(byWord: LineBreak) {
    Heading("이렇게 말을 걸어 보세요", "홈에서 대화 시작을 누른 뒤 \u201C자비스\u201D라고 부르고 이어서 말하면 돼요.", byWord)
    JarvisGroup {
        listOf("자비스, 오늘 일정 뭐 있어?", "자비스, 이 명함 기억해 줘", "자비스, 김 팀장님이랑 지난번에 무슨 얘기 했지?").forEach { phrase ->
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("“$phrase”", Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    val glasses = com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig.GLASSES
    Text(if (glasses) "스마트 안경은 홈에서 연결할 수 있어요. 안경이 없어도 휴대폰으로 바로 쓸 수 있어요."
        else "지원하는 스마트 안경이 있으면 나중에 연결해 함께 쓸 수 있어요.",
        style = MaterialTheme.typography.bodySmall.copy(lineBreak = byWord), color = MaterialTheme.colorScheme.onSurfaceVariant)
}
