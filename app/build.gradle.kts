import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.cairn.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cairn.reader"
        minSdk = 26
        targetSdk = 36
        versionCode = 116
        versionName = "3.93.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // Release signing is read from environment (CI) or a local keystore.properties.
    // When neither is present, the release build is left UNSIGNED (it still assembles) rather than
    // falling back to the world-known debug key — a debug-signed "release" would defeat update
    // integrity, so we never ship one by accident.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val hasReleaseSigning = System.getenv("CAIRN_KEYSTORE_PATH") != null ||
        System.getenv("CAIRN_KEYSTORE_BASE64") != null ||
        keystorePropsFile.exists()
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                val props = Properties()
                if (keystorePropsFile.exists()) props.load(keystorePropsFile.inputStream())
                val storePath = System.getenv("CAIRN_KEYSTORE_PATH") ?: props.getProperty("storeFile")
                storeFile = storePath?.let { file(it) }
                storePassword = System.getenv("CAIRN_KEYSTORE_PASSWORD") ?: props.getProperty("storePassword")
                keyAlias = System.getenv("CAIRN_KEY_ALIAS") ?: props.getProperty("keyAlias")
                keyPassword = System.getenv("CAIRN_KEY_PASSWORD") ?: props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 code shrinking + resource shrinking, guarded by proguard-rules.pro (keeps for
            // Room / Hilt / WorkManager / OkHttp / readability4j / the app's data & worker classes).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only sign when a real key is available; otherwise leave unsigned (never debug-key).
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint {
        // A missing/legacy-issue baseline keeps CI honest without blocking on pre-existing findings;
        // new issues fail `lint`. The release assembly isn't slowed by lint (it runs as its own gate).
        baseline = file("lint-baseline.xml")
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = false
        // The Spanish locale is an intentional partial "demo" translation that relies on Android's
        // per-string fallback to English; don't fail on the untranslated remainder.
        disable += "MissingTranslation"
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    // Per-ABI APK splits: each device downloads only its own native libraries (SQLCipher's
    // libsqlcipher.so alone is ~5.8 MB per ABI). A single-ABI APK is ~10 MB vs the ~25 MB universal.
    // A universal APK is still produced as a fallback for unknown ABIs / manual installs.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    // Installs the bundled ART baseline profile (src/main/baseline-prof.txt) on first run for the
    // API levels where the platform doesn't do it automatically, so hot code is AOT-compiled and
    // cold start / first-scroll jank drops.
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // SQLCipher: transparent whole-database encryption (key held in the Android Keystore).
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    // WorkManager + Hilt
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Storage / networking / images / parsing
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.readability4j)
    implementation(libs.jsoup)
    implementation(libs.androidx.documentfile)
    // On-device translation (ML Kit) is planned but removed for now to keep the APK lean.

    // Unit tests (JVM, Robolectric for Android-framework-touching pieces)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)

    // Instrumentation tests (Room migrations run on-device/emulator)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
}
