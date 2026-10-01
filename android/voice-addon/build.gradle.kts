import java.io.FileInputStream
import java.util.Properties

// :voice-addon — the Hexis Voice satellite (Phase 1), a separate installable APK.
//
// It holds RECORD_AUDIO (which the core never does), captures the mic in its own process, and returns
// only text to the core over the :bridge spine. No launcher surface; no settings UI (all controls
// live in the core). The real sherpa-onnx engine drops in behind the SttEngine interface later —
// this build ships an echo dev engine so the whole pipeline is exercisable offline and on-device
// before the native engine + model (which need network to fetch) are integrated.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Signing: the addon must ship under the SAME Hexis keyset as the core so the two trust each other
// in release builds (the core pins that keyset). Credentials come from a local keystore.properties or
// CI env vars; absent either, release falls back to the debug key so a build always succeeds.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

android {
    namespace = "com.wkhan.hexis.voice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wkhan.hexis.voice"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        resourceConfigurations += listOf("en")
        // The sherpa-onnx engine ships native libs; arm64-v8a only keeps the APK small and covers
        // effectively all modern devices (armeabi-v7a can be added later at a size cost).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        val storePathValue = signingValue("storeFile", "KEYSTORE_FILE")
        val storeFileResolved = storePathValue?.let { rootProject.file(it) }
        if (storeFileResolved != null && storeFileResolved.exists()) {
            create("release") {
                storeFile = storeFileResolved
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // The addon is tiny; no R8 needed. Sign with the Hexis release key when available, else debug.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":bridge"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // sherpa-onnx (k2-fsa) v1.13.8 — the offline streaming STT engine. Slimmed to arm64-v8a; bundles
    // the Kotlin API (com.k2fsa.sherpa.onnx) + native libs. Apache-2.0. Resolved via the flatDir repo
    // declared in settings.gradle.kts.
    implementation(":sherpa-onnx-1.13.8-arm64@aar")

    testImplementation("junit:junit:4.13.2")
}
