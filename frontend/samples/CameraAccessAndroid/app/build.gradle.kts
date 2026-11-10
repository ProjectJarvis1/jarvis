/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
  compileSdk = 36
  compileSdkMinor = 1
  ndkVersion = "27.0.12077973" // 16KB page alignment by default, needed for Play on Android 15+

  buildFeatures { buildConfig = true }

  defaultConfig {
    // Store identity; fixed once uploaded to Google Play. The code namespace above stays the Meta sample one.
    applicationId = "ai.jarvisglass.app"
    minSdk = 31
    targetSdk = 36
    versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
    versionName = (findProperty("versionName") as String?) ?: "0.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables { useSupportLibrary = true }
    ndk {
      abiFilters += listOf("arm64-v8a", "armeabi-v7a")
    }
    externalNativeBuild {
      cmake {
        // Android 15+ 16KB page devices: static C++ runtime, 16KB-aligned segments, no OpenMP runtime (its prebuilt is 4KB-aligned).
        arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384", "-DGGML_OPENMP=OFF")
      }
    }
  }

  // Upload key for Google Play lives outside the repo; see keystore.properties.example.
  val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
  }
  signingConfigs {
    if (keystoreProps.getProperty("storeFile") != null) {
      create("upload") {
        storeFile = file(keystoreProps.getProperty("storeFile"))
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
      }
    }
  }
  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.findByName("upload")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  buildFeatures { compose = true }
  composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
    }
  }
  packaging {
    resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    jniLibs { pickFirsts += "**/libc++_shared.so" }
  }
}

dependencies {
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20240303")
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.material3)
  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.mwdat.core)
  implementation(libs.mlkit.text.korean)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.mockdevice)
  // VisionClaw additions
  implementation(libs.okhttp)
  implementation(libs.webrtc)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.datastore.preferences)
  implementation(libs.gson)
  implementation(libs.lifecycle.process)
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.rules)
}
