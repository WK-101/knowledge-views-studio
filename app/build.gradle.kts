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
        versionCode = 62
        versionName = "3.36.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // Release signing is read from environment (CI) or a local keystore.properties.
    // When neither is present, release builds fall back to the debug key so the
    // project always assembles for development.
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
            // R8 stays off until the release build is verified on a device; the shipped
            // v0.1 APK then behaves exactly like the tested debug build, just signed.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
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

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

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
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.readability4j)
    implementation(libs.jsoup)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // On-device translation (downloadable language models, no account, nothing uploaded) +
    // language identification so we know what to translate from.
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    testImplementation(libs.junit)
}
