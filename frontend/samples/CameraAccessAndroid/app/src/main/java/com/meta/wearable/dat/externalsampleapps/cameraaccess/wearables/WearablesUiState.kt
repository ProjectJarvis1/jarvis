/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// WearablesUiState - DAT API State Management
//
// This data class aggregates DAT API state for the UI layer

package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class WearablesUiState(
    val registrationState: GlassesRegistration = GlassesRegistration.UNAVAILABLE,
    val devices: ImmutableList<String> = persistentListOf(),
    val recentError: String? = null,
    val isStreaming: Boolean = false,
    val hasMockDevices: Boolean = false,
    val isDebugMenuVisible: Boolean = false,
    val isGettingStartedSheetVisible: Boolean = false,
    val hasActiveDevice: Boolean = false,
    val isPhoneMode: Boolean = false,
    val isSettingsVisible: Boolean = false,
    val isMemoryVisible: Boolean = false,
) {
  val isRegistered: Boolean = registrationState == GlassesRegistration.REGISTERED || hasMockDevices
}
