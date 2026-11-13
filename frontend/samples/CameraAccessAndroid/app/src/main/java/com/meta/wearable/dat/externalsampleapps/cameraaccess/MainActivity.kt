/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess

import com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount

import android.Manifest.permission.CALL_PHONE
import android.Manifest.permission.CAMERA
import android.Manifest.permission.INTERNET
import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.READ_CONTACTS
import android.Manifest.permission.RECORD_AUDIO
import android.Manifest.permission.WRITE_CONTACTS
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.GlassesPlatform
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.CameraAccessScaffold
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

class MainActivity : ComponentActivity() {
  companion object {
    val PERMISSIONS: Array<String>
      get() =
          buildList {
                addAll(GlassesPlatform.permissions)
                add(INTERNET)
                add(RECORD_AUDIO)
                add(READ_CONTACTS)
                add(WRITE_CONTACTS)
                add(CALL_PHONE)
                add(CAMERA)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  add(POST_NOTIFICATIONS)
                }
              }
              .toTypedArray()
  }

  val viewModel: WearablesViewModel by viewModels()

  // Registered while the activity is created; asks for the glasses camera permission (always false on the phone build).
  private val requestGlassesCamera: suspend () -> Boolean = GlassesPlatform.permissionRequester(this)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    AppContextProvider.init(this)

    // Initialize settings with app context
    SettingsManager.init(this)
    com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount.init(this)
    lifecycleScope.launch {
      com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount.validate()
    }

    // Keep screen on while streaming
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    // First, ensure the app has necessary Android permissions
    checkPermissions {
      // Initialize the glasses SDK once the permissions are granted (nothing to do on the phone build)
      GlassesPlatform.initialize(this)

      // Start observing Wearables state after SDK is initialized
      viewModel.startMonitoring()
    }

    setContent {
      com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.JarvisTheme {
      // Records live on the server per account, so the app starts at sign-in until there is one.
      val user by com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount.user
          .collectAsState()
      // Signing out (or a rejected session) starts a fresh activity, so the next account never inherits
      // the previous one's in-memory tasks; on-device records are kept per user (JarvisAccount.dataDir).
      var wasSignedIn by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(user != null) }
      androidx.compose.runtime.LaunchedEffect(user) {
        if (user == null && wasSignedIn) {
          startActivity(android.content.Intent(this@MainActivity, MainActivity::class.java)
              .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
          finish()
        }
        wasSignedIn = user != null
      }
      if (user == null) {
        com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.SignInScreen()
      } else {
        CameraAccessScaffold(
            viewModel = viewModel,
            onRequestWearablesPermission = requestGlassesCamera,
        )
      }
      }
    }
  }

  fun checkPermissions(onPermissionsGranted: () -> Unit) {
    registerForActivityResult(RequestMultiplePermissions()) { permissionsResult ->
          val granted = permissionsResult.entries.all { it.value }
          if (granted) {
            onPermissionsGranted()
          } else {
            viewModel.setRecentError(
                "Allow All Permissions (Bluetooth scan/connect, notifications, microphone, camera)"
            )
          }
        }
        .launch(PERMISSIONS)
  }
}
