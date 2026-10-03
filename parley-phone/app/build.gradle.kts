import java.util.Locale
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.baselineprofile)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Sign the release only when a keystore is configured (keystore.properties or PARLEY_KEYSTORE). F-Droid
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
        // Keep these two plain literals. F-Droid's update check reads them line by line with a regex and can't
        // follow a variable or an expression. Bump both for a release, then tag v<versionName> (docs/RELEASING.md).
        versionCode = 21
        versionName = "5.1.0"
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
            // After F-Droid's strip this is an empty `if`; the line inside is the only reference to the config.
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

    // Parley is English-only. Libraries (Material 3, Compose) bring their few strings in about 80 languages; each
    // costs a full offset table in resources.arsc (4 bytes for every string Parley has), so only English is kept.
    androidResources { localeFilters += listOf("en") }

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
        // ez-vcard's hCard (HTML) writer template and its placeholder picture: Parley never writes HTML (that writer
        // needs FreeMarker, which isn't included).
        resources.excludes += "ezvcard/io/html/**"
        // DataStore's native counter is loaded only by multi-process DataStore, which Parley doesn't use.
        jniLibs.excludes += "**/libdatastore_shared_counter.so"
        // "Where is this number from" place names: only English and the app's other languages the geocoder has
        // data for (German, Spanish, French, Portuguese, Arabic; none for Hindi or Urdu). The Chinese set alone was
        // 790 KB. NumberInfo asks in English for any other language (GeoLanguages), so a dropped file is never read.
        resources.excludes += listOf(
            "be", "bg", "bs", "el", "fa", "fi", "hr", "hu", "hy", "id", "it", "iw", "ja", "kk", "ko", "nl", "pl", "ro",
            "ru", "sq", "sr", "sv", "th", "tr", "uk", "vi", "zh", "zh_Hant",
        ).map { "com/google/i18n/phonenumbers/geocoding/data/*_$it" }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Missing translations are warnings (they fall back to English); see lint.xml.
        lintConfig = rootProject.file("lint.xml")
    }

    testOptions { unitTests.isIncludeAndroidResources = true }
    // The fake Keystore and Contacts Provider are shared with core:data's Robolectric tests.
    sourceSets["test"].java.srcDir(rootProject.file("core/data/src/testShared/kotlin"))
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// `./gradlew :app:generateBaselineProfile` with a device connected writes src/release/generated/baselineProfiles/;
// never during an ordinary build (F-Droid builds without a device).
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.work)
    // Installs the baseline profile (app/src/main/baseline-prof.txt plus the generated one) on sideloaded and F-Droid
    // installs, which get no cloud profiles.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.core.splashscreen)
    baselineProfile(project(":baselineprofile"))
    debugImplementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // WorkManager's test helpers (a synchronous WorkManager and its test driver) for the To call reminder's worker.
    testImplementation(libs.androidx.work.testing)
    // Compose UI smoke tests under Robolectric (the five tabs and the call screen); the manifest library registers
    // the empty activity they render in, for the unit-test manifest only.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
}

// Privacy guard, an allow-list: the merged manifest may ask for exactly these permissions (plus the app's own
// signature permissions below). Anything a new dependency or manifest change brings in fails the build until it is
// reviewed and added here. Parley never gets network, location, camera, microphone, SMS or storage access.
val allowedPermissions = setOf(
    "android.permission.ANSWER_PHONE_CALLS",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.CALL_PHONE",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.GET_ACCOUNTS",
    // Hides other apps' overlays over sensitive screens (Android 12+), against tapjacking; a normal permission.
    "android.permission.HIDE_OVERLAY_WINDOWS",
    "android.permission.MODIFY_AUDIO_SETTINGS",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.READ_CALL_LOG",
    "android.permission.READ_CONTACTS",
    "android.permission.READ_PHONE_NUMBERS",
    "android.permission.READ_PHONE_STATE",
    "android.permission.READ_SYNC_SETTINGS",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.USE_BIOMETRIC",
    "android.permission.USE_FINGERPRINT",
    "android.permission.USE_FULL_SCREEN_INTENT",
    "android.permission.VIBRATE",
    "android.permission.WAKE_LOCK",
    "android.permission.WRITE_CALL_LOG",
    "android.permission.WRITE_CONTACTS",
)

// Never allowed, whatever the list above says; named in the error so the reason is clear.
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

// APK-size budget, the ≤ 12 MiB target (13.3 MiB at 4.3.0, 11.7 MiB after trimming, 12.2 MiB with 4.5's features,
// back under once Parley became English-only in 4.6; see docs/PERFORMANCE_BENCHMARKS.md): `./gradlew
// :app:checkReleaseApkSize` builds the release APK and fails above the budget, so growth is a decision rather than an
// accident. CI runs it.
val apkBudgetBytes = 12L * 1024 * 1024

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        if (variant.buildType == "release") {
            val apkDir = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK)
            val loader = variant.artifacts.getBuiltArtifactsLoader()
            tasks.register("check${cap}ApkSize") {
                group = "verification"
                description = "Fails when the ${variant.name} APK is larger than ${apkBudgetBytes / (1024 * 1024)} MiB."
                inputs.dir(apkDir)
                doLast {
                    val apks = loader.load(apkDir.get())?.elements.orEmpty().map { File(it.outputFile) }
                    if (apks.isEmpty()) throw GradleException("No ${variant.name} APK found in ${apkDir.get()}")
                    apks.forEach { apk ->
                        val mib = "%.2f".format(Locale.ROOT, apk.length() / (1024.0 * 1024.0))
                        if (apk.length() > apkBudgetBytes) {
                            throw GradleException("${apk.name} is $mib MiB, over the ${apkBudgetBytes / (1024 * 1024)} MiB budget (app/build.gradle.kts)")
                        }
                        logger.lifecycle("APK size: ${apk.name} is $mib MiB (budget ${apkBudgetBytes / (1024 * 1024)} MiB)")
                    }
                }
            }
        }
        // The app's own permissions: the lists companion's signature permission and AndroidX's receiver guard.
        val own = variant.applicationId.zip(variant.manifestPlaceholders.getting("listsPermission")) { id, lists ->
            setOf(lists, "$id.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        }
        val task = tasks.register("check${cap}Permissions") {
            group = "verification"
            description = "Fails when the merged ${variant.name} manifest asks for a permission that isn't allowed."
            val manifest = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
            inputs.file(manifest)
            doLast {
                val text = manifest.get().asFile.readText()
                val found = forbiddenPermissions.filter { text.contains("\"$it\"") }
                if (found.isNotEmpty()) throw GradleException("Forbidden permissions in merged manifest: $found")
                val used = Regex("<uses-permission(?:-sdk-23)?[^>]*android:name=\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toSet()
                val extra = used - allowedPermissions - own.get()
                if (extra.isNotEmpty()) throw GradleException("Permissions not on the allow-list in the merged manifest: $extra (see app/build.gradle.kts)")
                logger.lifecycle("Permission check passed for ${variant.name}")
            }
        }
        // Hooked to preBuild, which every build of the variant starts with (assemble, bundle, install, lint, unit tests).
        // preBuild can't depend on the check (the manifest merge itself depends on preBuild), so the check finalizes it:
        // it runs in the same build, after the merge, and fails that build. Packaging also waits for it.
        tasks.matching { it.name == "pre${cap}Build" }.configureEach { finalizedBy(task) }
        afterEvaluate { listOf("assemble$cap", "bundle$cap").forEach { tasks.findByName(it)?.dependsOn(task) } }
    }
}
