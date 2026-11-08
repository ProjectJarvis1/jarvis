package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NonStreamScreen(viewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var connectionHelp by remember { mutableStateOf(false) }
    JarvisHome(true, state.hasActiveDevice,
        { if (state.hasActiveDevice) viewModel.navigateToStreaming(onRequestWearablesPermission) else connectionHelp = true },
        viewModel::navigateToPhoneMode, modifier, onHelp = { connectionHelp = true })
    if (connectionHelp || state.isGettingStartedSheetVisible) {
        ModalBottomSheet(onDismissRequest = { connectionHelp = false; viewModel.hideGettingStartedSheet() },
            containerColor = Jarvis.extras.card) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("글라스와 연결할게요", style = MaterialTheme.typography.headlineSmall)
                Text("글라스를 착용하고 전원을 켠 뒤 Meta AI 앱에서 연결 상태와 카메라 권한을 확인해 주세요.",
                    style = MaterialTheme.typography.bodyMedium)
                Text("연결을 기다리는 동안 휴대폰 카메라로 자비스를 사용할 수 있어요.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                JarvisPrimaryButton("휴대폰으로 먼저 시작", { connectionHelp = false; viewModel.hideGettingStartedSheet(); viewModel.navigateToPhoneMode() },
                    Modifier.fillMaxWidth())
            }
        }
    }
}
