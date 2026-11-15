package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R

/*
 * The conversation look shared by home and the live screen, after the company site's phone demo: Jarvis speaks in
 * pale blue bubbles from the left with its face beside them, the user in solid blue from the right, and a small
 * grey line above a message says when and why Jarvis spoke.
 */

private val JarvisShape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
private val UserShape = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp, bottomStart = 20.dp)

/** Jarvis's face: the launcher character on its pale blue disc. */
@Composable
fun JarvisAvatar(size: Dp = 32.dp, modifier: Modifier = Modifier) {
    val ground = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.primaryContainer else Color(0xFFE4ECFF)
    Box(modifier.size(size).clip(CircleShape).background(ground), contentAlignment = Alignment.Center) {
        // The launcher foreground keeps the character inside a 66/108 safe zone; enlarge it to fill the disc.
        Image(painterResource(R.drawable.ic_launcher_jarvis_fg), null, Modifier.requiredSize(size * 1.45f))
    }
}

/** The grey line above a message: an icon, when, and what it is about. A tag (e.g. 확인 필요) may follow. */
@Composable
fun ChatMeta(text: String, icon: ImageVector? = null, tag: String? = null, tagTone: Color? = null, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(15.dp), tint = scheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        if (tag != null) Surface(shape = RoundedCornerShape(50), color = (tagTone ?: scheme.primary).copy(alpha = .12f)) {
            Text(tag, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold, color = tagTone ?: scheme.primary)
        }
    }
}

/**
 * A message from Jarvis: pale blue bubble from the left, and anything below the text (lists, actions). The face
 * sits once in the screen header, as in a chat with one person, so the bubbles keep the width.
 */
@Composable
fun JarvisBubble(text: String?, modifier: Modifier = Modifier, showFace: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null) {
    val extras = Jarvis.extras
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        if (showFace) JarvisAvatar(32.dp)
        Surface(Modifier.weight(1f, fill = false).widthIn(max = 340.dp), shape = JarvisShape, color = extras.jarvisBubble) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!text.isNullOrBlank()) Text(text, style = MaterialTheme.typography.bodyLarge, color = extras.onJarvisBubble)
                content?.invoke(this)
            }
        }
        Spacer(Modifier.width(40.dp))
    }
}

/** What the user said, on the right in solid blue. [pending] draws it outlined: heard, not answered yet. */
@Composable
fun UserBubble(text: String, modifier: Modifier = Modifier, pending: Boolean = false, caption: String? = null) {
    val extras = Jarvis.extras
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(Modifier.padding(start = 56.dp).widthIn(max = 320.dp), shape = UserShape,
            color = if (pending) MaterialTheme.colorScheme.background else extras.userBubble,
            border = if (pending) BorderStroke(1.dp, extras.userBubble.copy(alpha = .5f)) else null) {
            Text(text, Modifier.padding(horizontal = 14.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyLarge,
                color = if (pending) MaterialTheme.colorScheme.onSurface else extras.onUserBubble)
        }
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A reply chip under a Jarvis message. [main] is the suggested action (solid); the rest are outlined. */
@Composable
fun ChatAction(label: String, onClick: () -> Unit, main: Boolean = true) {
    val extras = Jarvis.extras
    Surface(onClick = onClick, modifier = Modifier.heightIn(min = 40.dp), shape = RoundedCornerShape(50),
        color = if (main) extras.userBubble else Jarvis.extras.card,
        border = if (main) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (main) extras.onUserBubble else MaterialTheme.colorScheme.primary)
        }
    }
}
