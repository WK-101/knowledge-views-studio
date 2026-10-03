import java.io.FileInputStream
import java.util.Properties

// :web-bridge — the Hexis Web Bridge satellite, a separate installable APK.
//
// It holds INTERNET (which the core never does), runs a local web server on the LAN, and serves a web UI
// that manages the core by calling the core's `data` capability over the :bridge spine. It holds NO core
// data and not the DB key — only the scoped data the user granted, fetched on demand. No launcher beyond
// its own control screen. Not a dependency of :app.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Signing: ship under the SAME Hexis keyset as the core so the two trust each other (the core pins that
// keyset AND gates its data provider with a signature permission). Credentials come from a local
// keystore.properties or CI env; absent either, release falls back to the debug key so a build succeeds.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

android {
    namespace = "com.wkhan.hexis.web"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wkhan.hexis.web"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations += listOf("en")
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
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Ktor pulls in a few java.* APIs; desugar so minSdk 26 is safe.
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/*.kotlin_module",
        )
    }
}

dependencies {
    implementation(project(":bridge"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Local web server: Ktor on the coroutine-based CIO engine (lighter than Netty on Android).
    val ktor = "2.3.12"
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")

    // QR of the pairing URL on the control screen.
    implementation("com.google.zxing:core:3.5.3")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    testImplementation("junit:junit:4.13.2")
}
