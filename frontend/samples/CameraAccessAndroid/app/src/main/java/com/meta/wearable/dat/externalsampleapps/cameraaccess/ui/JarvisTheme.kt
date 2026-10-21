package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Monochrome palette from the Jarvis design canvas: ink on white, grey surfaces, hairline borders.
private val LightPalette = lightColorScheme(
    primary = Color(0xFF0D0D0D), onPrimary = Color.White,
    primaryContainer = Color(0xFFF4F4F4), onPrimaryContainer = Color(0xFF0D0D0D),
    secondaryContainer = Color(0xFFF4F4F4), onSecondaryContainer = Color(0xFF0D0D0D),
    background = Color.White, onBackground = Color(0xFF0D0D0D),
    surface = Color.White, onSurface = Color(0xFF0D0D0D),
    surfaceVariant = Color(0xFFF4F4F4), onSurfaceVariant = Color(0xFF6B6B6B),
    surfaceContainer = Color.White, surfaceContainerLow = Color.White, surfaceContainerHigh = Color(0xFFF4F4F4),
    outline = Color(0xFFD4D4D4), outlineVariant = Color(0xFFE5E5E5),
    error = Color(0xFFC62828), errorContainer = Color(0xFFFDECEC),
)
private val DarkPalette = darkColorScheme(
    primary = Color(0xFFF2F2F2), onPrimary = Color(0xFF0D0D0D),
    primaryContainer = Color(0xFF242424), onPrimaryContainer = Color(0xFFF2F2F2),
    secondaryContainer = Color(0xFF242424), onSecondaryContainer = Color(0xFFF2F2F2),
    background = Color(0xFF0D0D0D), onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF0D0D0D), onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF242424), onSurfaceVariant = Color(0xFFA3A3A3),
    surfaceContainer = Color(0xFF0D0D0D), surfaceContainerLow = Color(0xFF0D0D0D), surfaceContainerHigh = Color(0xFF242424),
    outline = Color(0xFF525252), outlineVariant = Color(0xFF2E2E2E),
    error = Color(0xFFFF8A80),
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkPalette else LightPalette,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)),
        typography = Typography(
            headlineLarge = androidx.compose.ui.text.TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
            headlineMedium = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium),
            titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 21.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
            titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        ), content = content,
    )
}

@Composable
fun JarvisCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun JarvisActionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(icon, null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun JarvisTag(label: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small round status dot used across screens (connection, running work). */
@Composable
fun JarvisDot(color: Color = MaterialTheme.colorScheme.onSurface) {
    androidx.compose.foundation.layout.Box(Modifier.size(8.dp).background(color, androidx.compose.foundation.shape.CircleShape))
}
