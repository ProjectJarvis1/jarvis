/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// WearablesViewModel - Core DAT SDK Integration
//
// This ViewModel demonstrates the core DAT API patterns for:
// - Device registration and unregistration using the DAT SDK
// - Permission management for wearable devices
// - Device discovery and state management
// - Integration with MockDeviceKit for testing

package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Screen state around the glasses. The glasses SDK itself sits behind [GlassesPlatform] (glasses build only). */
class WearablesViewModel(application: Application) : AndroidViewModel(application) {
  companion object {
    private const val TAG = "WearablesViewModel"
  }

  private val _uiState = MutableStateFlow(WearablesUiState())
  val uiState: StateFlow<WearablesUiState> = _uiState.asStateFlow()

  private var monitoringStarted = false

  fun startMonitoring() {
    if (monitoringStarted) {
      return
    }
    monitoringStarted = true
    GlassesPlatform.monitor(viewModelScope, getApplication(), object : GlassesSink {
      override fun onActiveDevice(active: Boolean) {
        _uiState.update { it.copy(hasActiveDevice = active) }
      }

      override fun onRegistration(state: GlassesRegistration) {
        val previous = _uiState.value.registrationState
        val showGettingStarted = state == GlassesRegistration.REGISTERED && previous == GlassesRegistration.REGISTERING
        _uiState.update { it.copy(registrationState = state, isGettingStartedSheetVisible = showGettingStarted) }
      }

      override fun onDevices(ids: List<String>, hasMockDevices: Boolean) {
        _uiState.update { it.copy(devices = ids.toImmutableList(), hasMockDevices = hasMockDevices) }
      }

      override fun onError(message: String) = setRecentError(message)
    })
  }

  /** False until MainActivity initializes the glasses SDK (it waits for Android permissions). */
  private fun sdkReady(): Boolean {
    if (!GlassesPlatform.SUPPORTED) return false
    if (monitoringStarted) return true
    setRecentError("글라스 기능을 쓰려면 앱 권한을 모두 허용해 주세요. 휴대폰 카메라 대화는 지금도 쓸 수 있어요.")
    return false
  }

  fun startRegistration(activity: Activity) {
    if (!sdkReady()) return
    GlassesPlatform.startRegistration(activity)
  }

  fun startUnregistration(activity: Activity) {
    if (!sdkReady()) return
    GlassesPlatform.startUnregistration(activity)
  }

  /** Opens the conversation on the glasses camera, asking for the glasses camera permission first if needed. */
  fun navigateToStreaming(onRequestWearablesPermission: suspend () -> Boolean) {
    if (!sdkReady()) return
    viewModelScope.launch {
      val granted = GlassesPlatform.cameraPermissionGranted().getOrElse { error ->
        setRecentError(error.message ?: "글라스 카메라 권한을 확인하지 못했어요.")
        return@launch
      }
      if (granted || onRequestWearablesPermission()) {
        _uiState.update { it.copy(isStreaming = true) }
      } else {
        setRecentError("글라스 카메라 권한이 거부됐어요. Meta AI 앱의 앱 연결 화면에서 카메라를 허용해 주세요.")
      }
    }
  }

  fun navigateToPhoneMode() {
    _uiState.update { it.copy(isStreaming = true, isPhoneMode = true) }
  }

  fun navigateToDeviceSelection() {
    _uiState.update { it.copy(isStreaming = false, isPhoneMode = false) }
  }

  fun showSettings() {
    _uiState.update { it.copy(isSettingsVisible = true) }
  }

  fun hideSettings() {
    _uiState.update { it.copy(isSettingsVisible = false) }
  }

  fun showMemory() {
    _uiState.update { it.copy(isMemoryVisible = true) }
  }

  fun hideMemory() {
    _uiState.update { it.copy(isMemoryVisible = false) }
  }

  fun showDebugMenu() {
    _uiState.update { it.copy(isDebugMenuVisible = true) }
  }

  fun hideDebugMenu() {
    _uiState.update { it.copy(isDebugMenuVisible = false) }
  }

  fun clearCameraPermissionError() {
    _uiState.update { it.copy(recentError = null) }
  }

  fun setRecentError(error: String) {
    _uiState.update { it.copy(recentError = error) }
  }

  fun showGettingStartedSheet() {
    _uiState.update { it.copy(isGettingStartedSheetVisible = true) }
  }

  fun hideGettingStartedSheet() {
    _uiState.update { it.copy(isGettingStartedSheetVisible = false) }
  }

}
