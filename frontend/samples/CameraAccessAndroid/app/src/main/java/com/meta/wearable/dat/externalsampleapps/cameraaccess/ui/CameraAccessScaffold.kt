package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
    val tasks by geminiViewModel.taskRecords.collectAsStateWithLifecycle()
    var taskPage by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val tab = when { state.isMemoryVisible -> 3; taskPage -> 2; else -> 0 }
    val activeTasks = tasks.count { it.state in setOf(com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskState.QUEUED,
        com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskState.RUNNING,
        com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskState.WAITING_NETWORK,
        com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskState.WAITING_USER) }
    fun startConversation() {
        viewModel.hideMemory(); viewModel.hideSettings(); taskPage = false
        if (state.hasActiveDevice) viewModel.navigateToStreaming(onRequestWearablesPermission) else viewModel.navigateToPhoneMode()
    }
    LaunchedEffect(state.recentError) {
        state.recentError?.let { snackbar.showSnackbar(it); viewModel.clearCameraPermissionError() }
    }
    BackHandler(enabled = state.isMemoryVisible || state.isSettingsVisible || taskPage) {
        viewModel.hideMemory(); viewModel.hideSettings(); taskPage = false
    }
    Scaffold(modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!state.isStreaming) Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    val labels = listOf("홈", "대화", "작업", "기억")
                    val icons = listOf(Icons.Default.Home, Icons.Default.Mic, Icons.Default.Checklist, Icons.Default.Search)
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = {
                            when (index) {
                                1 -> startConversation()
                                else -> {
                                    viewModel.hideMemory(); viewModel.hideSettings(); taskPage = index == 2
                                    if (index == 3) viewModel.showMemory()
                                }
                            }
                        }, icon = {
                            BadgedBox(badge = { if (index == 2 && tasks.any { it.state == com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskState.WAITING_USER }) Badge() }) {
                                Icon(icons[index], null)
                            }
                        }, label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.surface,
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
                !state.isMemoryVisible && !state.isSettingsVisible && !taskPage -> {
                    if (state.isRegistered) NonStreamScreen(viewModel, onRequestWearablesPermission, activeTasks, { taskPage = true })
                    else HomeScreen(viewModel, activeTasks, { taskPage = true })
                }
            }
            if (taskPage && !state.isStreaming) TaskBoardScreen(geminiViewModel)
            if (state.isMemoryVisible) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                MemoryScreen(onBack = viewModel::hideMemory)
            }
            if (state.isSettingsVisible) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                SettingsScreen(onBack = viewModel::hideSettings,
                    onDebug = if (BuildConfig.DEBUG) viewModel::showDebugMenu else null,
                    onDisconnect = if (state.isRegistered && activity != null) { { viewModel.startUnregistration(activity) } } else null)
            }
            if (state.isDebugMenuVisible && BuildConfig.DEBUG) ModalBottomSheet(onDismissRequest = viewModel::hideDebugMenu) {
                MockDeviceKitScreen(Modifier.fillMaxHeight(.9f))
            }
        }
    }
}
