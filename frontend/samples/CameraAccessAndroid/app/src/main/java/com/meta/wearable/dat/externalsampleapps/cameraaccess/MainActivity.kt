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
    com.meta.wearable.dat.externalsampleapps.cameraaccess.crash.CrashReporter.install(this)

    // Initialize settings with app context
    SettingsManager.init(this)
    com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount.init(this)
    lifecycleScope.launch {
      com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount.validate()
      // A crash kept from an earlier run goes to Jarvis's own server once the session is known to be good.
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        com.meta.wearable.dat.externalsampleapps.cameraaccess.crash.CrashReporter.uploadPending(applicationContext)
      }
    }
    // Jarvis checks the calendar every 15 minutes, app open or not, to offer help first.
    com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.ProactiveWorker.schedule(this)
    if (savedInstanceState == null) openOffer(intent)

    // Keep screen on while streaming
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    if (BuildConfig.GLASSES) {
      // The glasses SDK needs its Bluetooth permissions before it starts, so the glasses build asks up front.
      checkPermissions {
        GlassesPlatform.initialize(this)
        viewModel.startMonitoring()
        askCalendarForOffers()
      }
    } else {
      // The phone build asks one permission at a time in the first-run guide (OnboardingScreen), and the
      // microphone again when a conversation starts. A "no" turns off only that one thing.
      GlassesPlatform.initialize(this)
      viewModel.startMonitoring()
      if (SettingsManager.onboardingDone) askCalendarForOffers()
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
      var onboarded by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(SettingsManager.onboardingDone) }
      if (user == null) {
        com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.SignInScreen()
      } else if (!onboarded) {
        // Once, right after the first sign-in on this phone.
        com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.OnboardingScreen(onDone = {
          SettingsManager.onboardingDone = true
          // The guide was the one start-up ask for the calendar ("먼저 알려 주기"); a skipped or declined row is not
          // asked again at the next launch. Settings explains why nothing arrives and can ask then.
          getSharedPreferences("proactive_permission", MODE_PRIVATE).edit().putBoolean("asked", true).apply()
          onboarded = true
        })
      } else {
        CameraAccessScaffold(
            viewModel = viewModel,
            onRequestWearablesPermission = requestGlassesCamera,
        )
      }
      }
    }
  }

  // "먼저 알려 주기" is on by default and reads the calendar; asked once after the app's own permissions.
  private val calendarPermissionLauncher =
      registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

  private fun askCalendarForOffers() {
    // Asked once; after a "no" the Settings row explains why nothing arrives and offers to allow it.
    val prefs = getSharedPreferences("proactive_permission", MODE_PRIVATE)
    if (prefs.getBoolean("asked", false)) return
    if (com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager.proactiveOffersEnabled &&
        checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      prefs.edit().putBoolean("asked", true).apply()
      calendarPermissionLauncher.launch(android.Manifest.permission.READ_CALENDAR)
    }
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    openOffer(intent)
  }

  /** A tapped offer notification: show that offer's detail, if it belongs to the signed-in account. */
  private fun openOffer(intent: android.content.Intent?) {
    val id = intent?.getStringExtra(
        com.meta.wearable.dat.externalsampleapps.cameraaccess.notify.JarvisNotifications.EXTRA_OFFER_ID) ?: return
    intent.removeExtra(com.meta.wearable.dat.externalsampleapps.cameraaccess.notify.JarvisNotifications.EXTRA_OFFER_ID)
    val store = com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.ProactiveStore
    // An offer whose event is over would still say "오늘 …"; the home list hides it, so this does not open it.
    store.find(this, id)
        ?.takeIf { com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.ProactivePlanner.isLive(it, System.currentTimeMillis()) }
        ?.let(store::open)
  }

  fun checkPermissions(onPermissionsGranted: () -> Unit) {
    registerForActivityResult(RequestMultiplePermissions()) { permissionsResult ->
          val granted = permissionsResult.entries.all { it.value }
          if (granted) {
            onPermissionsGranted()
          } else {
            viewModel.setRecentError("안경 연결에는 블루투스, 마이크, 카메라, 알림 권한이 필요해요. 휴대폰 설정에서 허용해 주세요.")
          }
        }
        .launch(PERMISSIONS)
  }
}
