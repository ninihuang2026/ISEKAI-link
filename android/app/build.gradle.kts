plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tools.isekai.cameraclient"
    compileSdk = 34

    defaultConfig {
        applicationId = "tools.isekai.cameraclient"
        // Matches the Rust core's own floor: seera-msquic's selfsign_openssl.c
        // calls glob()/globfree(), which bionic only declares starting at
        // API 28; the whole cross-compile targets 29 for consistency with
        // quictls's own hardcoded sub-build API level. See
        // ~/isekai-link-android-camera-client-setup.md on this machine for
        // the full story.
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Paired with Kotlin 1.9.24 per the Compose-compiler/Kotlin
        // compatibility map.
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    // UniFFI's generated Kotlin bindings load the native library via JNA.
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    // ProcessLifecycleOwner: whole-app foreground/background transitions, for
    // the suspend/resume-on-background policy (task 7) -- Android's
    // equivalent of iOS's scenePhase-driven connection suspend/resume
    // (docs/ios_camera_client_plan.md risk R3).
    implementation("androidx.lifecycle:lifecycle-process:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Auth0 login: hand-rolled Authorization Code + PKCE through a Custom Tab,
    // same choice iOS made (ASWebAuthenticationSession) over pulling in the
    // full Auth0 SDK -- three requests and a hash doesn't need a dependency.
    implementation("androidx.browser:browser:1.8.0")

    // Encrypted storage for the endpoint key PEM and the Auth0 session --
    // Android's equivalent of iOS's Keychain.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // QR pairing: CameraX for the preview/analysis pipeline, ML Kit for the
    // actual barcode decode.
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
