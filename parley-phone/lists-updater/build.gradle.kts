import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * "Parley Lists": the optional companion that downloads public spam lists (B4c). It is a separate app so that
 * Parley itself never gets the INTERNET permission, and this app never gets contacts, phone or call-log access.
 * It depends on :core:common (pack builder) and :core:ui (theme) only; never on :core:data or :app.
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Same signing key as Parley: its packs are shared through a signature permission.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.parley.lists"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.parley.lists"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        manifestPlaceholders["listsPermission"] = "app.parley.permission.READ_LISTS"
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProps.getProperty("storeFile") ?: System.getenv("PARLEY_KEYSTORE")
            if (storePath != null) {
                // Resolved like Parley's (relative to app/), so one keystore.properties serves both apps.
                storeFile = rootProject.file("app").resolve(storePath)
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
            val release = signingConfigs.getByName("release")
            if (release.storeFile != null) signingConfig = release
        }
        debug {
            // Mirrors Parley's debug build (app.parley.phone.debug), which looks for this package and permission.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["listsPermission"] = "app.parley.permission.READ_LISTS_DEBUG"
        }
    }

    buildFeatures { compose = true }

    // L1: per-app language. The locale list (android:localeConfig) is generated from the values-* folders, with
    // res/resources.properties naming the language of the default strings.
    androidResources { generateLocaleConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

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
    implementation(project(":core:ui"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work)
    implementation(libs.kotlinx.serialization.json)
}

// Privacy guard (allow-list): the updater may only reach the network. No contacts, phone, call log, SMS,
// location, camera, microphone or storage permission may ever end up in its merged manifest.
val allowedPermissions = setOf(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    // WorkManager's own install-time permissions: keep scheduled updates across reboots.
    "android.permission.WAKE_LOCK",
    "android.permission.RECEIVE_BOOT_COMPLETED",
)

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val appId = variant.applicationId
        val task = tasks.register("check${cap}UpdaterPermissions") {
            val manifest = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
            inputs.file(manifest)
            doLast {
                val text = manifest.get().asFile.readText()
                val used = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toSet()
                val own = setOf("${appId.get()}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
                val extra = used - allowedPermissions - own
                if (extra.isNotEmpty()) throw GradleException("Permissions not allowed in the lists updater: $extra")
                if ("sharedUserId" in text) throw GradleException("The lists updater must never use sharedUserId")
                logger.lifecycle("Updater permission check passed for ${variant.name}: $used")
            }
        }
        afterEvaluate { tasks.findByName("assemble$cap")?.dependsOn(task) }
    }
}
