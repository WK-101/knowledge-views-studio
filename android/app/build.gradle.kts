import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Signing credentials come from either a local `keystore.properties` file (developer machine)
// or environment variables (CI). If neither is available, release builds fall back to the debug
// key so a build always succeeds and produces an installable APK.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

val runNumber = System.getenv("GITHUB_RUN_NUMBER")

android {
    namespace = "com.todocompanion.app"
    compileSdk = 34

    defaultConfig {
        // R69 — the installed package id now carries the Kairo brand. (The code `namespace` stays
        // com.todocompanion.app so the R class / imports / class names are untouched; FileProvider
        // authorities are all built from the runtime packageName, so they follow this automatically.)
        applicationId = "com.wkhan.kairo"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber?.toIntOrNull() ?: 1
        versionName = "0.1.${runNumber ?: "0"}"
        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
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
    // R73 — expose the exported Room schema (app/schemas/) to instrumented tests as an asset, so a
    // MigrationTestHelper can load it and validate the migration chain against the real DB.
    sourceSets {
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// R73 — tell Room's KSP processor where to write the exported schema JSON (consumed by the
// instrumented MigrationTest, and committed so schema drift shows up in code review).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // Biometric / device-credential app lock (local only, no network)
    implementation("androidx.biometric:biometric:1.1.0")
    // R71 — FORCE a modern androidx.fragment. biometric:1.1.0 transitively pulls fragment 1.2.x, whose
    // FragmentActivity.checkForValidRequestCode() rejects the >16-bit request codes that the modern
    // activity-compose ActivityResultRegistry generates for a runtime-permission prompt. On a FRESH
    // install that mismatch crashed the app the instant it asked for the notifications permission
    // ("Can only use lower 16 bits for requestCode"). Fragment 1.6+ removed that legacy validator.
    implementation("androidx.fragment:fragment-ktx:1.8.4")

    // Storage Access Framework helper for folder-based backup & sync (local files only)
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Persistence — Room (local SQLite only)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // SQLCipher — transparent AES-256 encryption of the Room database at rest (fully local; the
    // native library adds no network permission). Key is held in the Android KeyStore (see SecureDb).
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite:2.4.0")

    // JSON export/import
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // QR encoding for offline proof-of-work verification (pure Java, no network, no extra permission).
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // R73 — instrumented tests: replay the whole Room migration chain against a real SQLite DB.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
