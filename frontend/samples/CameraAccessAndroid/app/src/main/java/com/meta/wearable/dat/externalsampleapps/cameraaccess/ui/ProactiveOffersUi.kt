package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.OfferKind
import com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.ProactiveOffer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.ProactiveStore

private val byWord = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)

private fun kindLabel(offer: ProactiveOffer) = if (offer.kind == OfferKind.MEETING) "오늘 미팅" else "다가오는 마감"

/**
 * What Jarvis offered on its own, newest first, so an offer is still reachable when notifications are off.
 * Offers whose event is over are left out. Shows nothing when there is none.
 */
@Composable
fun ProactiveOfferList(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ProactiveStore.load(context) }
    val offers by ProactiveStore.offers.collectAsState()
    // Re-read the clock every minute so an offer leaves the list once its event is over.
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000L); now = System.currentTimeMillis() } }
    val live = offers.filter { com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.ProactivePlanner.isLive(it, now) }.take(3)
    if (live.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Text("Jarvis가 먼저 챙긴 일", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        JarvisGroup {
            live.forEachIndexed { i, offer ->
                if (i > 0) HorizontalDivider(color = scheme.outlineVariant)
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable {
                    if (com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.ProactivePlanner.isLive(offer, System.currentTimeMillis()))
                        ProactiveStore.open(offer) else now = System.currentTimeMillis()
                }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.padding(top = 6.dp)) { JarvisDot(if (offer.kind == OfferKind.MEETING) scheme.primary else scheme.error, 8) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(kindLabel(offer), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        Text(offer.message, style = MaterialTheme.typography.bodyMedium.copy(lineBreak = byWord))
                    }
                }
            }
        }
    }
}

/** The accepted offer: what Jarvis gathered for it. Opened from the notification or the home list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProactiveOfferSheet() {
    val context = LocalContext.current
    val offer by ProactiveStore.opened.collectAsState()
    val current = offer ?: return
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = { ProactiveStore.open(null) }, containerColor = Jarvis.extras.card) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            JarvisStatusChip(kindLabel(current), dot = if (current.kind == OfferKind.MEETING) scheme.primary else scheme.error)
            Text(current.title, style = MaterialTheme.typography.headlineSmall.copy(lineBreak = byWord))
            val lines = current.detail.lines().filter { it.isNotBlank() }
            // The first line repeats the date and title; the rest is what was found.
            lines.drop(1).forEach { line ->
                val (head, rest) = line.split(": ", limit = 2).let { if (it.size == 2) it[0] to it[1] else "" to it[0] }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (head.isNotEmpty()) Text(head, style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                    Text(rest, style = MaterialTheme.typography.bodyMedium.copy(lineBreak = byWord))
                }
            }
            Text(lines.firstOrNull().orEmpty(), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            JarvisPrimaryButton("확인했어요", { ProactiveStore.open(null) }, Modifier.fillMaxWidth())
            JarvisSecondaryButton("목록에서 지우기", { ProactiveStore.dismiss(context, current.id); ProactiveStore.open(null) },
                Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
    }
}
