package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Phone build: there is no glasses simulator to show. */
@Composable
fun GlassesDebugPanel(modifier: Modifier = Modifier) {
  Text("휴대폰 전용 빌드에는 글라스 시험 도구가 없어요.", modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
}
