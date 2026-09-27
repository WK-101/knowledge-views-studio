import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Generates the app's baseline profile and runs the macrobenchmarks (cold start to Recents, a Recents fling, typing on
// the keypad, the incoming-call screen). Needs a device or emulator; see docs/PERFORMANCE_BENCHMARKS.md.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "app.parley.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        lintConfig = rootProject.file("lint.xml")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// A connected phone or emulator (no Gradle-managed device: nothing is downloaded at configuration time).
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
