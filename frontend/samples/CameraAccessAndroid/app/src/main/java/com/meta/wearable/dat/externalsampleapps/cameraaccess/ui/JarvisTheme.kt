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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Light blue palette from the Figma "자비스 글라스 사용자 워크플로우 · 버전 2". Values are sampled from a view-only
// render, so they are close to, not exactly, the designer's tokens. See DESIGN.md.
private val LightPalette = lightColorScheme(
    primary = Color(0xFF2B50B8), onPrimary = Color.White,
    primaryContainer = Color(0xFFE7EDFC), onPrimaryContainer = Color(0xFF1C3576),
    secondaryContainer = Color(0xFFF1F2F6), onSecondaryContainer = Color(0xFF16181D),
    background = Color.White, onBackground = Color(0xFF16181D),
    surface = Color.White, onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFF1F2F6), onSurfaceVariant = Color(0xFF5F6673),
    surfaceContainer = Color(0xFFF4F5F7), surfaceContainerLow = Color(0xFFF4F5F7),
    surfaceContainerHigh = Color(0xFFF1F2F6), surfaceContainerHighest = Color(0xFFE9EBF0),
    outline = Color(0xFFD5D9E2), outlineVariant = Color(0xFFE8EAEF),
    error = Color(0xFFD93025), errorContainer = Color(0xFFFDECEC), onErrorContainer = Color(0xFFA1211A),
)
private val DarkPalette = darkColorScheme(
    primary = Color(0xFF9DB4F2), onPrimary = Color(0xFF0E1D45),
    primaryContainer = Color(0xFF1E2A4A), onPrimaryContainer = Color(0xFFD6E0FB),
    secondaryContainer = Color(0xFF242833), onSecondaryContainer = Color(0xFFECEEF3),
    background = Color(0xFF0F1115), onBackground = Color(0xFFECEEF3),
    surface = Color(0xFF1A1D24), onSurface = Color(0xFFECEEF3),
    surfaceVariant = Color(0xFF242833), onSurfaceVariant = Color(0xFFA0A6B3),
    surfaceContainer = Color(0xFF0F1115), surfaceContainerLow = Color(0xFF0F1115),
    surfaceContainerHigh = Color(0xFF242833), surfaceContainerHighest = Color(0xFF2D3240),
    outline = Color(0xFF454B5A), outlineVariant = Color(0xFF2A2F3B),
    error = Color(0xFFFF8A80), errorContainer = Color(0xFF3A1F1E), onErrorContainer = Color(0xFFFFDAD6),
)

/** Colors the Material scheme has no slot for: the grouped-screen ground and the primary button gradient. */
@Immutable
data class JarvisExtras(
    val ground: Color,
    val card: Color,
    val gradientStart: Color,
    val gradientEnd: Color,
    val onGradient: Color,
    val stop: Color,
    val onStop: Color,
)

private val LightExtras = JarvisExtras(
    ground = Color(0xFFF4F5F7), card = Color.White,
    gradientStart = Color(0xFFE8EEFD), gradientEnd = Color(0xFFA9C0F4), onGradient = Color(0xFF1C2E66),
    stop = Color(0xFF191A1E), onStop = Color.White,
)
private val DarkExtras = JarvisExtras(
    ground = Color(0xFF0F1115), card = Color(0xFF1A1D24),
    gradientStart = Color(0xFF2A3A66), gradientEnd = Color(0xFF4A66B0), onGradient = Color(0xFFEFF3FF),
    stop = Color(0xFFECEEF3), onStop = Color(0xFF16181D),
)

val LocalJarvisExtras = staticCompositionLocalOf { LightExtras }

object Jarvis {
    val extras: JarvisExtras @Composable get() = LocalJarvisExtras.current
}

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalJarvisExtras provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(
            colorScheme = if (dark) DarkPalette else LightPalette,
            shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp)),
            typography = koreanWrap(Typography(
                displayLarge = TextStyle(fontSize = 56.sp, lineHeight = 64.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
                headlineLarge = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
                headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
                headlineSmall = TextStyle(fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
                titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold),
                titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
                titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
                labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
            )), content = content,
        )
    }
}

// Korean wraps between words (phrases), never inside one, in every text style.
private val ByWord = androidx.compose.ui.text.style.LineBreak.Paragraph.copy(
    wordBreak = androidx.compose.ui.text.style.LineBreak.WordBreak.Phrase)

private fun koreanWrap(t: Typography): Typography {
    fun TextStyle.w() = copy(lineBreak = ByWord, localeList = androidx.compose.ui.text.intl.LocaleList("ko-KR"))
    return t.copy(
        displayLarge = t.displayLarge.w(), displayMedium = t.displayMedium.w(), displaySmall = t.displaySmall.w(),
        headlineLarge = t.headlineLarge.w(), headlineMedium = t.headlineMedium.w(), headlineSmall = t.headlineSmall.w(),
        titleLarge = t.titleLarge.w(), titleMedium = t.titleMedium.w(), titleSmall = t.titleSmall.w(),
        bodyLarge = t.bodyLarge.w(), bodyMedium = t.bodyMedium.w(), bodySmall = t.bodySmall.w(),
        labelLarge = t.labelLarge.w(), labelMedium = t.labelMedium.w(), labelSmall = t.labelSmall.w(),
    )
}

/** The main action: a soft light-to-blue gradient with dark navy text, as on every Figma screen. */
@Composable
fun JarvisPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    leading: ImageVector? = null) {
    val x = Jarvis.extras
    val shape = RoundedCornerShape(12.dp)
    Surface(onClick = onClick, enabled = enabled, shape = shape, color = Color.Transparent,
        modifier = modifier.heightIn(min = 52.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .background(if (enabled) Brush.horizontalGradient(listOf(x.gradientStart, x.gradientEnd))
                else Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)), shape)
            .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            ButtonLabel(text, leading, if (enabled) x.onGradient else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Secondary action next to a primary one (취소, 다시 확인): flat grey. */
@Composable
fun JarvisSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    leading: ImageVector? = null) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.heightIn(min = 52.dp)) {
        Box(Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            ButtonLabel(text, leading, MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .4f))
        }
    }
}

/** Stopping something that is running (회의 녹음 종료): solid ink, the only black button in the app. */
@Composable
fun JarvisStopButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    val x = Jarvis.extras
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = x.stop, modifier = modifier.heightIn(min = 52.dp)) {
        Box(Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            ButtonLabel(text, leading, x.onStop)
        }
    }
}

@Composable
private fun ButtonLabel(text: String, leading: ImageVector?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (leading != null) Icon(leading, null, Modifier.size(18.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, textAlign = TextAlign.Center)
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
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { i, label ->
                val on = i == selectedIndex
                Surface(onClick = { onSelect(i) }, shape = RoundedCornerShape(9.dp),
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

/** The 자비스 wordmark used at the top of the home screens: blue dot plus name. */
@Composable
fun JarvisWordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        JarvisDot(MaterialTheme.colorScheme.primary, 7)
        Text("자비스", style = MaterialTheme.typography.titleMedium)
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
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small round status dot used across screens (connection, running work). */
@Composable
fun JarvisDot(color: Color = MaterialTheme.colorScheme.primary, size: Int = 8) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}
