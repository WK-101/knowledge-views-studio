plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.parley.ui"
    buildFeatures { compose = true }
    // Theme.kt's top level touches android.graphics.Color (system-bar scrims); the token tests don't need real values.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    api(project(":core:common"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.material.icons)
    implementation(libs.androidx.core.ktx)
    // System-bar icon colours follow the app theme (enableEdgeToEdge with the theme's dark/light).
    implementation(libs.androidx.activity.compose)
    // Plain JVM tests of the design tokens (contrast of the brand and AMOLED schemes).
    testImplementation(libs.junit)
}
