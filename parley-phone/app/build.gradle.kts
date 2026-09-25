import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// G1/D1: sign the release only when a keystore is configured (keystore.properties or PARLEY_KEYSTORE). F-Droid
// deletes the `signingConfigs { }` block and every line starting with `signingConfig =` before it builds, so no line
// that survives that strip may refer to the signing config. Without a keystore, assembleRelease is unsigned.
// Checked by tools/fdroid-strip-check.sh.
val releaseStorePath: String? = keystoreProps.getProperty("storeFile") ?: System.getenv("PARLEY_KEYSTORE")

android {
    namespace = "app.parley"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.parley.phone"
        minSdk = 29
        targetSdk = 36
        // D4: keep these two plain literals. F-Droid's update check reads them line by line with a regex and can't
        // follow a variable or an expression. Bump both for a release, then tag v<versionName> (docs/RELEASING.md).
        versionCode = 4
        versionName = "3.1.0"
        // Custom permission guarding the private-name lookup provider (differs in debug so both builds can be installed).
        manifestPlaceholders["lookupPermission"] = "app.parley.permission.LOOKUP_PRIVATE_NAME"
        // Optional "Parley Lists" companion (B4c, module :lists-updater): its package and signature permission.
        manifestPlaceholders["listsPackage"] = "app.parley.lists"
        manifestPlaceholders["listsPermission"] = "app.parley.permission.READ_LISTS"
    }

    signingConfigs {
        create("release") {
            if (releaseStorePath != null) {
                storeFile = file(releaseStorePath)
                storePassword = keystoreProps.getProperty("storePassword") ?: System.getenv("PARLEY_KEYSTORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("keyAlias") ?: System.getenv("PARLEY_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("keyPassword") ?: System.getenv("PARLEY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // G1: after F-Droid's strip this is an empty `if`; the line inside is the only reference to the config.
            if (releaseStorePath != null) {
                signingConfig = signingConfigs.findByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["lookupPermission"] = "app.parley.permission.LOOKUP_PRIVATE_NAME_DEBUG"
            manifestPlaceholders["listsPackage"] = "app.parley.lists.debug"
            manifestPlaceholders["listsPermission"] = "app.parley.permission.READ_LISTS_DEBUG"
        }
    }

    buildFeatures { compose = true }

    // L1: per-app language. The locale list (android:localeConfig) is generated from the values-* folders, with
    // res/resources.properties naming the language of the default strings.
    androidResources { generateLocaleConfig = true }
    // The in-app language picker (Android 10-12) needs every language in the APK, also when built as a bundle.
    bundle { language { enableSplit = false } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Reproducible-build friendly: no signed dependency blob, no VCS info.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/**/LICENSE*", "kotlin/**", "DebugProbesKt.bin")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Missing translations are warnings (they fall back to English); see lint.xml.
        lintConfig = rootProject.file("lint.xml")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":telecom"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.zxing.core)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.work)
    debugImplementation(libs.compose.ui.tooling.preview)
}

// Privacy guard: fail the build if any forbidden permission sneaks into the merged manifest.
val forbiddenPermissions = listOf(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.RECORD_AUDIO",
    "android.permission.CAMERA",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.READ_SMS",
    "android.permission.SEND_SMS",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "com.google.android.gms.permission.AD_ID",
)

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register("check${cap}Permissions") {
            val manifest = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
            inputs.file(manifest)
            doLast {
                val text = manifest.get().asFile.readText()
                val found = forbiddenPermissions.filter { text.contains("\"$it\"") }
                if (found.isNotEmpty()) throw GradleException("Forbidden permissions in merged manifest: $found")
                logger.lifecycle("Permission check passed for ${variant.name}")
            }
        }
        afterEvaluate { tasks.findByName("assemble$cap")?.dependsOn(task) }
    }
}
