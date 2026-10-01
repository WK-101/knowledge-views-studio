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

android {
    namespace = "com.wkhan.hexis.voice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wkhan.hexis.voice"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations += listOf("en")
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

    testImplementation("junit:junit:4.13.2")
}
