import java.util.Properties

/**
 * "Parley Lists": the optional companion that downloads public spam lists. It is a separate app so that
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

// As in app/build.gradle.kts, only the block and the line F-Droid strips refer to the signing config, so its
// build stays valid (and unsigned). Checked by tools/fdroid-strip-check.sh.
val releaseStorePath: String? = keystoreProps.getProperty("storeFile") ?: System.getenv("PARLEY_KEYSTORE")

android {
    namespace = "app.parley.lists"
    defaultConfig {
        applicationId = "app.parley.lists"
        // Plain literals only (F-Droid's update check reads them with a regex). Tag lists-v<versionName>.
        versionCode = 3
        versionName = "1.1.1"
        manifestPlaceholders["listsPermission"] = "app.parley.permission.READ_LISTS"
    }

    signingConfigs {
        create("release") {
            if (releaseStorePath != null) {
                // Resolved like Parley's (relative to app/), so one keystore.properties serves both apps.
                storeFile = rootProject.file("app").resolve(releaseStorePath)
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
            if (releaseStorePath != null) {
                signingConfig = signingConfigs.findByName("release")
            }
        }
        debug {
            // Mirrors Parley's debug build (app.parley.phone.debug), which looks for this package and permission.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["listsPermission"] = "app.parley.permission.READ_LISTS_DEBUG"
        }
    }

    buildFeatures { compose = true }

    // English-only, like Parley.
    androidResources { localeFilters += listOf("en") }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        // Compressed code, as in Parley: a smaller download for a slightly slower install (docs/PERFORMANCE_BENCHMARKS.md).
        dex.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/*.version", "META-INF/**/LICENSE*", "kotlin/**", "DebugProbesKt.bin")
        // ez-vcard comes along with :core:common, but none of its code survives shrinking here (the updater reads no
        // vCards), so its messages, licence copies and HTML template are dead weight.
        resources.excludes += "ezvcard/**"
        // libphonenumber's data comes packed with :core:common (PhoneData); the updater never looks up area names.
        resources.excludes += listOf(
            "com/google/i18n/phonenumbers/data/PhoneNumberMetadataProto_*",
            "com/google/i18n/phonenumbers/data/ShortNumberMetadataProto_*",
            "com/google/i18n/phonenumbers/geocoding/**",
            "com/google/i18n/phonenumbers/timezones/**",
            "app/parley/common/phone/area_names.bin",
        )
    }

    lint {
        checkReleaseBuilds = true
    }
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
        // As in the app: run with every build of the variant (preBuild), and before packaging.
        tasks.matching { it.name == "pre${cap}Build" }.configureEach { finalizedBy(task) }
        afterEvaluate { listOf("assemble$cap", "bundle$cap").forEach { tasks.findByName(it)?.dependsOn(task) } }
    }
}
