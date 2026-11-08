package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraAccessScaffold(viewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus, modifier: Modifier = Modifier,
    geminiViewModel: GeminiSessionViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = androidx.activity.compose.LocalActivity.current
    val snackbar = remember { SnackbarHostState() }
    val tab = when { state.isMemoryVisible -> 1; state.isSettingsVisible -> 2; else -> 0 }
    // Glasses-first: when the glasses are connected at launch, go straight to the conversation screen, which
    // waits for "자비스". Only once per app run, so leaving the conversation is not undone.
    var autoEntered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.hasActiveDevice) {
        if (state.hasActiveDevice && !autoEntered && !state.isStreaming) {
            autoEntered = true
            viewModel.hideMemory(); viewModel.hideSettings()
            viewModel.navigateToStreaming(onRequestWearablesPermission)
        }
    }
    LaunchedEffect(state.recentError) {
        state.recentError?.let { snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long); viewModel.clearCameraPermissionError() }
    }
    BackHandler(enabled = state.isMemoryVisible || state.isSettingsVisible) {
        viewModel.hideMemory(); viewModel.hideSettings()
    }
    Scaffold(modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!state.isStreaming) Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = Jarvis.extras.card, tonalElevation = 0.dp) {
                    val labels = listOf("대화", "기억", "설정")
                    // Filled when selected, outlined otherwise, as in the Figma tab bar.
                    val icons = listOf(Icons.Filled.ChatBubble to Icons.Outlined.ChatBubbleOutline,
                        Icons.Filled.Bookmark to Icons.Outlined.BookmarkBorder, Icons.Filled.Tune to Icons.Outlined.Tune)
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = {
                            viewModel.hideMemory(); viewModel.hideSettings()
                            if (index == 1) viewModel.showMemory()
                            if (index == 2) viewModel.showSettings()
                        }, icon = { Icon(if (tab == index) icons[index].first else icons[index].second, null, Modifier.size(22.dp)) },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                selectedIconColor = MaterialTheme.colorScheme.onSurface, selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                }
            }
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // Keep the session composed while records/settings cover it: switching pages does not stop work.
            when {
                state.isStreaming -> StreamScreen(viewModel, isPhoneMode = state.isPhoneMode, isForeground = !state.isMemoryVisible && !state.isSettingsVisible, geminiViewModel = geminiViewModel)
                !state.isMemoryVisible && !state.isSettingsVisible -> {
                    if (state.isRegistered) NonStreamScreen(viewModel, onRequestWearablesPermission)
                    else HomeScreen(viewModel)
                }
            }
            if (state.isMemoryVisible) Surface(Modifier.fillMaxSize(), color = Jarvis.extras.ground) {
                MemoryScreen(onBack = viewModel::hideMemory)
            }
            if (state.isSettingsVisible) Surface(Modifier.fillMaxSize(), color = Jarvis.extras.ground) {
                SettingsScreen(onBack = viewModel::hideSettings,
                    onDebug = if (BuildConfig.DEBUG) viewModel::showDebugMenu else null,
                    onDisconnect = if (state.isRegistered && activity != null) { { viewModel.startUnregistration(activity) } } else null,
                    glassesStatus = when { state.hasActiveDevice -> "연결됨"; state.isRegistered -> "기다리는 중"; else -> "연결 안 됨" })
            }
            if (state.isDebugMenuVisible && BuildConfig.DEBUG) ModalBottomSheet(onDismissRequest = viewModel::hideDebugMenu) {
                MockDeviceKitScreen(Modifier.fillMaxHeight(.9f))
            }
        }
    }
}
