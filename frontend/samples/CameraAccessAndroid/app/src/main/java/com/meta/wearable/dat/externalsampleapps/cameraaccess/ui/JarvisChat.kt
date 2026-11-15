package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R

/*
 * The conversation look shared by home and the live screen, after the company site's phone demo: Jarvis speaks in
 * pale blue bubbles from the left with its face beside them, the user in solid blue from the right, and a small
 * grey line above a message says when and why Jarvis spoke.
 */

private val JarvisShape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
private val UserShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)

/** Jarvis's face: the site's blue character, drawn bare like the site's phone header. */
@Composable
fun JarvisAvatar(size: Dp = 32.dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_jarvis_face), null, modifier.size(size))
}

/** The small bold line above a message: an icon, when, and what it is about. A tag (e.g. 확인이 필요해요) may follow. */
@Composable
fun ChatMeta(text: String, icon: ImageVector? = null, tag: String? = null, tagTone: Color? = null, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = scheme.onSurfaceVariant)
        // The tag text uses the deep end of its tone so it stays readable on its own pale tint.
        if (tag != null) Surface(shape = RoundedCornerShape(50), color = (tagTone ?: scheme.primary).copy(alpha = .12f)) {
            Text(tag, Modifier.padding(horizontal = 7.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall,
                color = if (tagTone == null || tagTone == scheme.primary) scheme.onPrimaryContainer else tagTone)
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
        if (showFace) JarvisAvatar(28.dp)
        Surface(Modifier.weight(1f, fill = false).widthIn(max = 340.dp), shape = JarvisShape, color = extras.jarvisBubble) {
            Column(Modifier.padding(start = 13.dp, end = 13.dp, top = 9.dp, bottom = if (content != null) 4.dp else 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!text.isNullOrBlank()) Text(text, style = MaterialTheme.typography.bodyLarge, color = extras.onJarvisBubble)
                content?.invoke(this)
            }
        }
        Spacer(Modifier.width(36.dp))
    }
}

/** What the user said, on the right in solid blue. [pending] draws it outlined: heard, not answered yet. */
@Composable
fun UserBubble(text: String, modifier: Modifier = Modifier, pending: Boolean = false, caption: String? = null) {
    val extras = Jarvis.extras
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(Modifier.padding(start = 48.dp).widthIn(max = 320.dp), shape = UserShape,
            color = if (pending) MaterialTheme.colorScheme.background else extras.userBubble,
            border = if (pending) BorderStroke(1.dp, extras.userBubble.copy(alpha = .5f)) else null) {
            Text(text, Modifier.padding(horizontal = 13.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyLarge,
                color = if (pending) MaterialTheme.colorScheme.onSurface else extras.onUserBubble)
        }
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A reply chip under a Jarvis message, as small as the site's: a 32dp pill drawn inside a 48dp touch box. [main] is
 * the suggested action (solid blue); the rest are white with a thin line.
 */
@Composable
fun ChatAction(label: String, onClick: () -> Unit, main: Boolean = true) {
    val extras = Jarvis.extras
    Box(Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp),
        contentAlignment = Alignment.Center) {
        Surface(Modifier.heightIn(min = 32.dp), shape = RoundedCornerShape(50),
            color = if (main) extras.blue else extras.card,
            border = if (main) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = if (main) extras.onBlue else MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}
