// :bridge — the Hexis Bridge SDK spine (Phase 0).
//
// A small, stable transport shared by the core app and every satellite addon. It carries typed,
// self-describing capability contracts over one fixed AIDL interface; adding an addon means adding
// a capability contract, never changing this module's transport. Pure infrastructure: no Android
// components, no permissions, no UI. See docs/design/addon-bridge-and-voice.md.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.wkhan.hexis.bridge"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    // The transport spine is defined in AIDL (off by default in AGP 8).
    buildFeatures {
        aidl = true
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
    // Envelopes and capability payloads are kotlinx.serialization JSON — the same version and the
    // same "tolerate unknown keys" discipline the core already uses for AppJson, so a newer peer's
    // extra fields never break an older one.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
}
