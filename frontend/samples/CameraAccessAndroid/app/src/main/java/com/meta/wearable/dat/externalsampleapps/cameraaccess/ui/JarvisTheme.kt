package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The company site's palette (crunchlabs.projectjarvis.biz, ~/develop/crunch-site/DESIGN.md): one blue that belongs to
// Jarvis and the main action, deep navy ink, cool pale grounds. Dark mode carries the same blue onto navy. See DESIGN.md.
private val SiteBlue = Color(0xFF3B6CF6)
private val LightPalette = lightColorScheme(
    primary = SiteBlue, onPrimary = Color.White,
    primaryContainer = Color(0xFFE4ECFF), onPrimaryContainer = Color(0xFF1F3F9E),
    secondaryContainer = Color(0xFFEEF2FA), onSecondaryContainer = Color(0xFF0E1B33),
    background = Color.White, onBackground = Color(0xFF0E1B33),
    surface = Color.White, onSurface = Color(0xFF0E1B33),
    surfaceVariant = Color(0xFFEEF2FA), onSurfaceVariant = Color(0xFF55627A),
    surfaceContainer = Color(0xFFF6F8FC), surfaceContainerLow = Color(0xFFF6F8FC),
    surfaceContainerHigh = Color(0xFFEEF2FA), surfaceContainerHighest = Color(0xFFE3E8F2),
    outline = Color(0xFFC9D3E6), outlineVariant = Color(0xFFE3E8F2),
    error = Color(0xFFD93025), errorContainer = Color(0xFFFDECEC), onErrorContainer = Color(0xFFA1211A),
)
private val DarkPalette = darkColorScheme(
    primary = Color(0xFF8EAEFF), onPrimary = Color(0xFF0B1A44),
    primaryContainer = Color(0xFF1C2A4F), onPrimaryContainer = Color(0xFFD3DFFF),
    secondaryContainer = Color(0xFF1C2538), onSecondaryContainer = Color(0xFFE8EDF7),
    background = Color(0xFF0B111D), onBackground = Color(0xFFE8EDF7),
    surface = Color(0xFF141B2B), onSurface = Color(0xFFE8EDF7),
    surfaceVariant = Color(0xFF1C2538), onSurfaceVariant = Color(0xFF9AA7C2),
    surfaceContainer = Color(0xFF0B111D), surfaceContainerLow = Color(0xFF0B111D),
    surfaceContainerHigh = Color(0xFF1C2538), surfaceContainerHighest = Color(0xFF253049),
    outline = Color(0xFF3A4660), outlineVariant = Color(0xFF222C40),
    error = Color(0xFFFF8A80), errorContainer = Color(0xFF3A1F1E), onErrorContainer = Color(0xFFFFDAD6),
)

/** Colors the Material scheme has no slot for: the grouped-screen ground, the solid blue fill, the stop button. */
@Immutable
data class JarvisExtras(
    val ground: Color,
    val card: Color,
    /** The site's blue as a fill (main buttons, the user's bubbles, the mic). The same in both modes. */
    val blue: Color,
    val onBlue: Color,
    val stop: Color,
    val onStop: Color,
    /** Conversation bubbles: what the user said (solid blue) and what Jarvis said or offered (pale blue). */
    val userBubble: Color,
    val onUserBubble: Color,
    val jarvisBubble: Color,
    val onJarvisBubble: Color,
)

private val LightExtras = JarvisExtras(
    ground = Color(0xFFF6F8FC), card = Color.White,
    blue = SiteBlue, onBlue = Color.White,
    stop = Color(0xFF0E1B33), onStop = Color.White,
    userBubble = SiteBlue, onUserBubble = Color.White,
    jarvisBubble = Color(0xFFEEF2FA), onJarvisBubble = Color(0xFF0E1B33),
)
private val DarkExtras = JarvisExtras(
    ground = Color(0xFF0B111D), card = Color(0xFF141B2B),
    blue = SiteBlue, onBlue = Color.White,
    stop = Color(0xFFE8EDF7), onStop = Color(0xFF0E1B33),
    userBubble = SiteBlue, onUserBubble = Color.White,
    jarvisBubble = Color(0xFF1C2A4F), onJarvisBubble = Color(0xFFE8EDF7),
)

val LocalJarvisExtras = staticCompositionLocalOf { LightExtras }

object Jarvis {
    val extras: JarvisExtras @Composable get() = LocalJarvisExtras.current
}

/** Pretendard, the site's typeface (SIL OFL 1.1, license in assets/licenses). Three weights cover the type scale. */
private val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_regular, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalJarvisExtras provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(
            colorScheme = if (dark) DarkPalette else LightPalette,
            shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp)),
            // The site's scale, tightened for a phone held for a glance: 15sp body, small bold labels, heavy titles.
            typography = koreanWrap(Typography(
                displayLarge = TextStyle(fontSize = 56.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.6).sp),
                headlineLarge = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
                headlineMedium = TextStyle(fontSize = 23.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
                headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
                titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
                titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
                titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
                bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
                labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
            )),
        ) {
            // Screens that draw their own background (sign-in, first-run, home) put text straight on it, outside any
            // Surface; without this their default text color stays black in dark mode.
            androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                content = content)
        }
    }
}

// Korean wraps between words (phrases), never inside one, in every text style.
private val ByWord = androidx.compose.ui.text.style.LineBreak.Paragraph.copy(
    wordBreak = androidx.compose.ui.text.style.LineBreak.WordBreak.Phrase)

private fun koreanWrap(t: Typography): Typography {
    fun TextStyle.w() = copy(fontFamily = Pretendard, lineBreak = ByWord, localeList = androidx.compose.ui.text.intl.LocaleList("ko-KR"))
    return t.copy(
        displayLarge = t.displayLarge.w(), displayMedium = t.displayMedium.w(), displaySmall = t.displaySmall.w(),
        headlineLarge = t.headlineLarge.w(), headlineMedium = t.headlineMedium.w(), headlineSmall = t.headlineSmall.w(),
        titleLarge = t.titleLarge.w(), titleMedium = t.titleMedium.w(), titleSmall = t.titleSmall.w(),
        bodyLarge = t.bodyLarge.w(), bodyMedium = t.bodyMedium.w(), bodySmall = t.bodySmall.w(),
        labelLarge = t.labelLarge.w(), labelMedium = t.labelMedium.w(), labelSmall = t.labelSmall.w(),
    )
}

/** The main action: the site's solid blue pill with white text. */
@Composable
fun JarvisPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    leading: ImageVector? = null) {
    val x = Jarvis.extras
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape,
        color = if (enabled) x.blue else MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.heightIn(min = 52.dp)) {
        Box(Modifier.heightIn(min = 52.dp).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            ButtonLabel(text, leading, if (enabled) x.onBlue else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Secondary action next to a primary one (취소, 다시 확인): a pale pill in ink. */
@Composable
fun JarvisSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    leading: ImageVector? = null) {
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.heightIn(min = 52.dp)) {
        Box(Modifier.heightIn(min = 52.dp).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            ButtonLabel(text, leading, MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .4f))
        }
    }
}

/** Stopping something that is running (회의 녹음 종료): solid ink, the only dark button in the app. */
@Composable
fun JarvisStopButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    val x = Jarvis.extras
    Surface(onClick = onClick, shape = CircleShape, color = x.stop, modifier = modifier.heightIn(min = 52.dp)) {
        Box(Modifier.heightIn(min = 52.dp).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            ButtonLabel(text, leading, x.onStop)
        }
    }
}

@Composable
private fun ButtonLabel(text: String, leading: ImageVector?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (leading != null) Icon(leading, null, Modifier.size(18.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp), color = color, textAlign = TextAlign.Center)
    }
}

/** A white group on the grey ground, used by 기억 and 설정. No border: the ground does the separating. */
@Composable
fun JarvisGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = Jarvis.extras.card) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

/** One row inside a [JarvisGroup]: icon tile, title, optional subtitle, and something on the right. */
@Composable
fun JarvisRow(title: String, modifier: Modifier = Modifier, subtitle: String? = null, icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant, iconContainer: Color = MaterialTheme.colorScheme.surfaceVariant,
    leading: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val clickable = if (onClick != null) Modifier.clip(MaterialTheme.shapes.small)
        .clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(modifier.fillMaxWidth().then(clickable).heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            leading != null -> leading()
            icon != null -> Surface(shape = RoundedCornerShape(10.dp), color = iconContainer, modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = iconTint) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Grey track with a white pill for the selected option (기억 | 회의). */
@Composable
fun JarvisSegmented(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { i, label ->
                val on = i == selectedIndex
                Surface(onClick = { onSelect(i) }, shape = CircleShape,
                    color = if (on) Jarvis.extras.card else Color.Transparent,
                    shadowElevation = if (on) 1.dp else 0.dp,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                        .semantics { selected = on }) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.titleSmall,
                            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** A small status pill: dot plus a few words, on a tinted ground. */
@Composable
fun JarvisStatusChip(label: String, dot: Color = MaterialTheme.colorScheme.primary,
    container: Color = MaterialTheme.colorScheme.primaryContainer, content: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Surface(shape = RoundedCornerShape(50), color = container) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            JarvisDot(dot, 6)
            Text(label, style = MaterialTheme.typography.labelMedium, color = content)
        }
    }
}

/** The name at the top of sign-in, first-run and glasses home: Jarvis's face plus name, as in the site header. */
@Composable
fun JarvisWordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        JarvisAvatar(28.dp)
        Text("Jarvis", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun JarvisCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = Jarvis.extras.card,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun JarvisTag(label: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small round status dot used across screens (connection, running work). */
@Composable
fun JarvisDot(color: Color = MaterialTheme.colorScheme.primary, size: Int = 8) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}
