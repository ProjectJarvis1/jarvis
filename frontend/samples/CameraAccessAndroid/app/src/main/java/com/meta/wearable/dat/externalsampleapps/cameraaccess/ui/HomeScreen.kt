package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@Composable
fun HomeScreen(viewModel: WearablesViewModel, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    if (!com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig.GLASSES) {
        PhoneHome(viewModel::navigateToPhoneMode, modifier)
        return
    }
    JarvisHome(false, false, { activity?.let(viewModel::startRegistration) }, viewModel::navigateToPhoneMode, modifier)
}

/** Phone build: no glasses to link, so home is the way into a conversation with the phone camera and mic. */
@Composable
private fun PhoneHome(onStart: () -> Unit, modifier: Modifier = Modifier) {
    val byWord = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            JarvisWordmark(Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("자비스에게 말을 걸어 보세요", style = MaterialTheme.typography.headlineLarge.copy(lineBreak = byWord))
            Spacer(Modifier.height(8.dp))
            Text("휴대폰 카메라와 마이크로 보고 듣고,\n기억해 둔 미팅과 사람을 찾아 알려 드려요.",
                style = MaterialTheme.typography.bodyMedium.copy(lineBreak = byWord), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            TryList()
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            JarvisPrimaryButton("대화 시작", onStart, Modifier.fillMaxWidth())
        }
    }
}

/** The glasses mark from the company site: two round lenses and a bridge. */
@Composable
fun JarvisMark(modifier: Modifier = Modifier) {
    Icon(painterResource(R.drawable.ic_jarvis_mark), null, modifier, tint = MaterialTheme.colorScheme.onSurface)
}

private enum class GlassesStage { NotLinked, Waiting, Ready }

/**
 * Home is only the way to the glasses. Every request happens in the conversation screen by saying "자비스",
 * so this screen shows where the glasses are and offers one next step. [onGlasses] registers, opens connection
 * help, or starts the conversation depending on the state; [onPhone] uses the phone camera instead.
 */
@Composable
fun JarvisHome(registered: Boolean, deviceReady: Boolean, onGlasses: () -> Unit, onPhone: () -> Unit,
    modifier: Modifier = Modifier, onHelp: (() -> Unit)? = null) {
    val stage = when { deviceReady -> GlassesStage.Ready; registered -> GlassesStage.Waiting; else -> GlassesStage.NotLinked }
    val (heading, body) = when (stage) {
        GlassesStage.Ready -> "글라스가 연결됐어요" to "글라스에 대고 \"자비스\"라고 부르면\n무엇이든 요청할 수 있어요."
        GlassesStage.Waiting -> "글라스를 켜 주세요" to "글라스를 쓰고 전원을 켜면\n바로 대화 화면으로 넘어가요."
        GlassesStage.NotLinked -> "글라스를 연결하세요" to "한 번 연결해 두면 글라스에 대고\n\"자비스\"라고 불러 요청할 수 있어요."
    }
    // Korean wraps by word (phrase), not in the middle of a word.
    val byWord = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            JarvisWordmark(Modifier.weight(1f))
            // While waiting, the main button is the help itself, so the corner link would only repeat it.
            if (onHelp != null && stage != GlassesStage.Waiting) TextButton(onClick = onHelp, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("연결 도움말", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Text(heading, style = MaterialTheme.typography.headlineLarge.copy(lineBreak = byWord))
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium.copy(lineBreak = byWord),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            GlassesPanel(stage)
            Spacer(Modifier.height(24.dp))
            if (stage == GlassesStage.Ready) TryList() else ConnectSteps(stage)
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (stage) {
                GlassesStage.Ready -> JarvisPrimaryButton("대화 시작", onGlasses, Modifier.fillMaxWidth())
                GlassesStage.Waiting -> JarvisSecondaryButton("연결 도움말", onGlasses, Modifier.fillMaxWidth())
                GlassesStage.NotLinked -> JarvisPrimaryButton("글라스 연결하기", onGlasses, Modifier.fillMaxWidth())
            }
            if (stage != GlassesStage.Ready) TextButton(onClick = onPhone, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("휴대폰으로 먼저 시작", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The picture of the glasses: grey while unlinked, blue and turning while waiting, filled with a check once ready. */
@Composable
private fun GlassesPanel(stage: GlassesStage) {
    val scheme = MaterialTheme.colorScheme
    val tinted = stage != GlassesStage.NotLinked
    val ground = if (tinted) Brush.verticalGradient(listOf(scheme.primaryContainer.copy(alpha = .55f), scheme.primaryContainer))
        else Brush.verticalGradient(listOf(scheme.surfaceVariant, scheme.surfaceVariant))
    Box(Modifier.fillMaxWidth().height(184.dp).background(ground, RoundedCornerShape(18.dp))) {
        Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
            when (stage) {
                GlassesStage.NotLinked -> JarvisStatusChip("글라스 연결 안 됨", dot = scheme.outline, container = Jarvis.extras.card,
                    content = scheme.onSurfaceVariant)
                GlassesStage.Waiting -> JarvisStatusChip("글라스 기다리는 중", container = Jarvis.extras.card, content = scheme.primary)
                GlassesStage.Ready -> JarvisStatusChip("글라스 연결됨", container = Jarvis.extras.card, content = scheme.primary)
            }
        }
        Box(Modifier.align(Alignment.Center).size(112.dp), contentAlignment = Alignment.Center) {
            if (stage == GlassesStage.Waiting) WaitingArc(Modifier.fillMaxSize())
            val ready = stage == GlassesStage.Ready
            Surface(shape = CircleShape, modifier = Modifier.size(if (ready) 96.dp else 92.dp),
                color = if (ready) scheme.primary.copy(alpha = .22f) else Jarvis.extras.card,
                border = if (ready) BorderStroke(6.dp, Jarvis.extras.card) else BorderStroke(1.dp, scheme.outlineVariant)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_jarvis_mark), null, Modifier.size(46.dp),
                        tint = when (stage) { GlassesStage.NotLinked -> scheme.outline; GlassesStage.Waiting -> scheme.primary
                            GlassesStage.Ready -> scheme.onSurface })
                }
            }
            if (ready) Surface(shape = CircleShape, color = scheme.primary, border = BorderStroke(2.dp, Jarvis.extras.card),
                modifier = Modifier.align(Alignment.BottomEnd).offset((-6).dp, (-6).dp).size(24.dp)) {
                Icon(Icons.Default.Check, "연결됨", Modifier.padding(4.dp), tint = scheme.onPrimary)
            }
        }
    }
}

/** A quarter arc turning around the glasses while the app waits for them. Still in previews. */
@Composable
private fun WaitingArc(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val angle = if (LocalInspectionMode.current) 0f else {
        val t by rememberInfiniteTransition(label = "wait").animateFloat(0f, 360f,
            infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "arc")
        t
    }
    Canvas(modifier) {
        drawArc(color, startAngle = angle - 90f, sweepAngle = 110f, useCenter = false,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** The three steps to linked glasses. The current one is ringed; finished ones are checked. */
@Composable
private fun ConnectSteps(stage: GlassesStage) {
    val scheme = MaterialTheme.colorScheme
    val steps = listOf("Meta AI 앱에서 글라스 등록", "글라스를 쓰고 전원 켜기", "카메라 권한 확인")
    val current = if (stage == GlassesStage.Waiting) 1 else 0
    Text("연결 순서", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    steps.forEachIndexed { i, step ->
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                i < current -> Surface(shape = CircleShape, color = scheme.primary, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Check, "완료", Modifier.padding(4.dp), tint = scheme.onPrimary)
                }
                i == current && stage == GlassesStage.Waiting -> Surface(shape = CircleShape, color = Jarvis.extras.card,
                    border = BorderStroke(1.5.dp, scheme.primary), modifier = Modifier.size(24.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                    }
                }
                else -> Surface(shape = CircleShape, color = scheme.surfaceVariant, modifier = Modifier.size(24.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                }
            }
            Text(step, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = if (i < current) scheme.onSurfaceVariant else scheme.onSurface)
            if (i == current && stage == GlassesStage.Waiting) Text("기다리는 중", style = MaterialTheme.typography.labelMedium,
                color = scheme.primary)
        }
    }
}

/** Once linked, three things to say, one per kind of request, so the first try works. */
@Composable
private fun TryList() {
    val scheme = MaterialTheme.colorScheme
    Text("이렇게 불러보세요", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    listOf("시야" to "\"자비스, 이게 뭐야?\"", "기억" to "\"이 사람 저장해줘\"", "일정" to "\"내일 오후 4시 미팅 캘린더에 넣어줘\"")
        .forEach { (kind, phrase) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(kind, Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                Text(phrase, style = MaterialTheme.typography.bodyMedium)
            }
        }
}
