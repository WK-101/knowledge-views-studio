import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.Properties

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
    defaultConfig {
        applicationId = "app.parley.phone"
        // Keep these two plain literals. F-Droid's update check reads them line by line with a regex and can't
        // follow a variable or an expression. Bump both for a release, then tag v<versionName> (docs/RELEASING.md).
        versionCode = 36
        versionName = "6.4.0"
        // The instrumented smoke tests in src/androidTest (a device or emulator: docs/PERFORMANCE_BENCHMARKS.md).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            manifestPlaceholders["listsPackage"] = "app.parley.lists.debug"
            manifestPlaceholders["listsPermission"] = "app.parley.permission.READ_LISTS_DEBUG"
        }
    }

    buildFeatures { compose = true }

    // Parley is English-only. Libraries (Material 3, Compose) bring their few strings in about 80 languages; each
    // costs a full offset table in resources.arsc (4 bytes for every string Parley has), so only English is kept.
    androidResources { localeFilters += listOf("en") }

    // Reproducible-build friendly: no signed dependency blob, no VCS info.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        // Compressed code: F-Droid and sideloaded installs download the APK byte for byte, and the dex is most of
        // it (about 10 MB stored, under 5 MB compressed). The cost is paid once on the phone: the installer unpacks
        // the code (a slightly slower install, and about 4 MiB more storage in all). Baseline profiles work the same.
        // docs/PERFORMANCE_BENCHMARKS.md has the numbers.
        dex.useLegacyPackaging = true
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
        // Area names for China and Australia, the two largest files (about 580 KB of the APK): those numbers show
        // the country only. NumberInfo never reads them (GeoLanguages.COUNTRIES_WITHOUT_AREAS; keep both in step).
        resources.excludes += listOf("86", "61").map { "com/google/i18n/phonenumbers/geocoding/data/${it}_*" }
    }

    lint {
        checkReleaseBuilds = true
    }

    testOptions { unitTests.isIncludeAndroidResources = true }
    // The fake Keystore and Contacts Provider are shared with core:data's Robolectric tests.
    sourceSets["test"].java.srcDir(rootProject.file("core/data/src/testShared/kotlin"))
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
    implementation(libs.androidx.work)
    // Installs the baseline profile (app/src/main/baseline-prof.txt plus the generated one) on sideloaded and F-Droid
    // installs, which get no cloud profiles.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.core.splashscreen)
    baselineProfile(project(":baselineprofile"))
    debugImplementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.androidx.exifinterface)
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

    // A small instrumented smoke suite (src/androidTest): needs a device or emulator, never runs in a plain build.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // The runner's own monitor is older than core's; core's (already verified) is the one used.
    androidTestImplementation(libs.androidx.test.runner) { exclude(group = "androidx.test", module = "monitor") }
    androidTestImplementation(libs.androidx.test.uiautomator)
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

// Download-size budget: the APK file as F-Droid and sideload users fetch it, compressed code included (about 6 MiB
// at 5.5; 11.6 MiB before the code was compressed; see docs/PERFORMANCE_BENCHMARKS.md for the installed size).
// `./gradlew :app:checkReleaseApkSize` builds the release APK and fails above the budget, so growth is a decision
// rather than an accident. CI runs it.
val apkBudgetBytes = 8L * 1024 * 1024

// Resources the platform keeps by name across updates, so collapsing names must leave them as they are: icons of
// launcher shortcuts made in code (app/src/main/kotlin/app/parley/shortcuts/Shortcuts.kt).
val keptResourceNames = listOf("drawable/ic_shortcut_add")

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        if (variant.buildType == "release") {
            // Collapsed resource names: every key in resources.arsc (5,500 names such as "set_amoled_title") becomes
            // one shared placeholder, about 150 KB less of a file Android requires to be stored uncompressed. Parley's
            // code never looks a resource up by name (no getIdentifier or getResourceName; the only library lookups
            // are Android's own dimens), but the platform does once: the launcher shortcuts' service saves a resource
            // icon by name and finds it again by that name after each update. Those icons keep their names
            // (keptResourceNames). AGP 8.13 runs aapt2 optimize but keeps this switch off, so the task's output is
            // optimised once more in place.
            val aapt2 = androidComponents.sdkComponents.sdkDirectory.map { it.dir("build-tools/${android.buildToolsVersion}").file("aapt2").asFile }
            val keptNames = layout.buildDirectory.file("intermediates/collapse_resource_names/$cap/resources.cfg")
            tasks.matching { it.name == "optimize${cap}Resources" }.configureEach {
                inputs.property("collapseResourceNames", true)
                inputs.property("keptResourceNames", keptResourceNames)
                doLast {
                    val tool = aapt2.get()
                    val cfg = keptNames.get().asFile.apply { parentFile.mkdirs() }
                    cfg.writeText(keptResourceNames.joinToString("\n", postfix = "\n") { "$it#no_collapse" })
                    outputs.files.asFileTree.matching { include("**/*.ap_") }.files.forEach { ap ->
                        val collapsed = File(ap.parentFile, "${ap.name}.collapsed")
                        val proc = ProcessBuilder(
                            tool.path, "optimize", "--collapse-resource-names", "--resources-config-path", cfg.path, "-o", collapsed.path, ap.path,
                        )
                            .redirectErrorStream(true).start()
                        val log = proc.inputStream.bufferedReader().readText()
                        if (proc.waitFor() != 0) throw GradleException("aapt2 optimize --collapse-resource-names failed for ${ap.name}: $log")
                        Files.move(collapsed.toPath(), ap.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            val apkDir = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK)
            val loader = variant.artifacts.getBuiltArtifactsLoader()
            tasks.register("check${cap}ApkSize") {
                group = "verification"
                description = "Fails when the ${variant.name} APK (the download) is larger than ${apkBudgetBytes / (1024 * 1024)} MiB."
                inputs.dir(apkDir)
                doLast {
                    val apks = loader.load(apkDir.get())?.elements.orEmpty().map { File(it.outputFile) }
                    if (apks.isEmpty()) throw GradleException("No ${variant.name} APK found in ${apkDir.get()}")
                    apks.forEach { apk ->
                        val mib = "%.2f".format(Locale.ROOT, apk.length() / (1024.0 * 1024.0))
                        if (apk.length() > apkBudgetBytes) {
                            throw GradleException("${apk.name} is $mib MiB, over the ${apkBudgetBytes / (1024 * 1024)} MiB budget (app/build.gradle.kts)")
                        }
                        logger.lifecycle("Download size: ${apk.name} is $mib MiB (budget ${apkBudgetBytes / (1024 * 1024)} MiB)")
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
