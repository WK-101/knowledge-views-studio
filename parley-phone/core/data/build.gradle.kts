plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.parley.data"
    // Robolectric tests read the module's strings, and the Room migration tests read the exported schemas.
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets["test"].assets.srcDir("$projectDir/schemas")
    // Test helpers shared with the app module's Robolectric tests (fake Keystore, fake providers).
    sourceSets["test"].java.srcDir("src/testShared/kotlin")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    api(libs.kotlinx.coroutines.android)
    // Editor drafts in saved state (ContactDraftJson).
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.libphonenumber)
    implementation(libs.phone.geocoder)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core:common")))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    // Writes settings files the way older Parley versions did, to test moving them over (PreferenceFileTest).
    testImplementation(libs.androidx.datastore.preferences)
}
