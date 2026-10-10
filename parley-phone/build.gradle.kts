plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover) apply false
}

/*
 * One Android and Kotlin setup for every module: SDK levels from the version catalog, Java 17 bytecode and the shared
 * lint rules. A module's own build file keeps only what is its own (namespace, features, dependencies).
 */
val compileSdkLevel = libs.versions.compileSdk.get().toInt()
val targetSdkLevel = libs.versions.targetSdk.get().toInt()
val minSdkLevel = libs.versions.minSdk.get().toInt()
subprojects {
    plugins.withType<com.android.build.gradle.api.AndroidBasePlugin> {
        extensions.configure<com.android.build.api.dsl.CommonExtension<*, *, *, *, *, *>>("android") {
            compileSdk = compileSdkLevel
            defaultConfig.minSdk = minSdkLevel
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            lint {
                abortOnError = true
                lintConfig = rootProject.file("lint.xml")
            }
        }
    }
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> { defaultConfig.targetSdk = targetSdkLevel }
    }
    plugins.withId("com.android.test") {
        extensions.configure<com.android.build.api.dsl.TestExtension> { defaultConfig.targetSdk = targetSdkLevel }
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/*
 * Test coverage (Kover), on request only, so everyday builds and test runs aren't instrumented:
 *   ./gradlew -Pcoverage koverHtmlReport koverXmlReport
 * writes one report over the modules below to build/reports/kover/ (html/index.html, report.xml). It reports and
 * never fails the build: there are no thresholds yet. Per module: ./gradlew -Pcoverage :core:common:koverHtmlReport.
 */
val coveredModules = listOf(":core:common", ":core:data", ":core:ui", ":telecom", ":app")
if (providers.gradleProperty("coverage").isPresent) {
    apply(plugin = "org.jetbrains.kotlinx.kover")
    subprojects {
        if (path in coveredModules) apply(plugin = "org.jetbrains.kotlinx.kover")
    }
    dependencies { coveredModules.forEach { "kover"(project(it)) } }
}

/*
 * Android lint can't see text written into Compose code, so this task looks for the usual shapes of it
 * (Text("…"), contentDescription = "…", toasts, notification texts, TalkBack actions) in the UI modules and lists
 * them as warnings. It runs before every lint task; -PfailOnHardcodedText=true turns the warnings into a failure.
 * A line that must stay as it is (a format pattern, a brand name) can carry the comment `// l10n-ok`.
 */
val checkHardcodedText by tasks.registering {
    group = "verification"
    description = "Lists user-visible text written straight into Kotlin UI code (L1)."
    val sources = files("app/src/main/kotlin", "telecom/src/main/kotlin", "core/ui/src/main/kotlin", "lists-updater/src/main/kotlin")
    inputs.files(sources).withPropertyName("sources")
    val fail = providers.gradleProperty("failOnHardcodedText").map { it.toBoolean() }.orElse(false)
    val root = layout.projectDirectory.asFile
    doLast {
        // A string literal with at least two letters in a row (so "%02d", " · " and "" pass).
        val lit = "\"(?:[^\"\\\\]|\\\\.)*[A-Za-z]{2,}(?:[^\"\\\\]|\\\\.)*\""
        val patterns = listOf(
            Regex("""\bText\(\s*$lit"""),
            Regex("""\b(?:contentDescription|onClickLabel|onLongClickLabel|stateDescription|actionLabel)\s*=\s*$lit"""),
            Regex("""CustomAccessibilityAction\(\s*$lit"""),
            Regex("""Toast\.makeText\([^,]+,\s*$lit"""),
            Regex("""\.set(?:ContentTitle|ContentText|SubText|SummaryText|BigContentTitle)\(\s*$lit"""),
            Regex("""\.addAction\(\s*0\s*,\s*$lit"""),
            Regex("""\b(?:toast|showMessage|showSnackbar|systemMessage)\(\s*$lit"""),
        )
        // String templates ("${n.count}", "$name") and \uXXXX escapes hold no words a translator could see.
        val notText = Regex("""\$\{[^}]*\}|\$[A-Za-z_]\w*|\\u[0-9A-Fa-f]{4}""")
        val hits = sources.asFileTree.matching { include("**/*.kt") }.files.sorted().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trim()
                val scan = code.replace(notText, "#")
                if (code.startsWith("*") || line.contains("l10n-ok") || patterns.none { it.containsMatchIn(scan) }) null
                else "${f.relativeTo(root)}:${i + 1}: ${code.take(140)}"
            }
        }
        if (hits.isEmpty()) {
            logger.lifecycle("checkHardcodedText: no hard-coded UI text found")
        } else {
            hits.forEach { logger.warn("warning: hard-coded UI text: $it") }
            logger.warn("checkHardcodedText: ${hits.size} line(s) with hard-coded UI text; move them to string resources")
            if (fail.get()) throw GradleException("Hard-coded UI text found (${hits.size} lines)")
        }
    }
}

/*
 * Stored, synced, exported and parsed values must not follow the app language: with Arabic (or Hindi/Urdu on some
 * devices) `"%d".format(x)` writes non-ASCII digits. This task lists `.format(` / `String.format(` calls with a
 * `%` pattern but no Locale in the data modules (core:common, core:data) as warnings. Display-only lines can carry
 * `// locale-ok`.
 */
val checkLocaleFormat by tasks.registering {
    group = "verification"
    description = "Lists locale-dependent String.format calls in the data modules."
    val sources = files("core/common/src/main/kotlin", "core/data/src/main/kotlin")
    inputs.files(sources).withPropertyName("sources")
    val root = layout.projectDirectory.asFile
    doLast {
        val call = Regex(""""[^"]*%[^"]*"\s*\.format\(|String\.format\(""")
        val hits = sources.asFileTree.matching { include("**/*.kt") }.files.sorted().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || line.contains("locale-ok") || !call.containsMatchIn(code) || code.contains("Locale.")) null
                else "${f.relativeTo(root)}:${i + 1}: ${code.take(140)}"
            }
        }
        if (hits.isEmpty()) {
            logger.lifecycle("checkLocaleFormat: no locale-dependent formatting found")
        } else {
            hits.forEach { logger.warn("warning: locale-dependent format: $it (use Locale.ROOT)") }
        }
    }
}

subprojects {
    tasks.matching { it.name.startsWith("lint") && it.name != "lintFix" }.configureEach {
        dependsOn(rootProject.tasks.named("checkHardcodedText"))
        dependsOn(rootProject.tasks.named("checkLocaleFormat"))
    }
}

/*
 * Unit tests: Robolectric fetches its Android runtime jar on first use. Take it from the same Maven Central mirror
 * the build uses (Central rate-limits shared CI egress). Test JVMs stay small so they fit next to the Gradle daemon.
 */
subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
        maxHeapSize = "1536m"
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}

/*
 * Static analysis: one detekt run over every module's Kotlin (main and test), with detekt's defaults, Parley's rules
 * (config/detekt/detekt.yml) and ktlint formatting through detekt-formatting. Findings that predate it are in
 * config/detekt/baseline.xml, so the task fails only on new ones. `./gradlew detektBaseline` rewrites the baseline.
 */
detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(files("config/detekt/detekt.yml"))
    baseline = file("config/detekt/baseline.xml")
    source.setFrom(
        listOf("app", "core/common", "core/data", "core/ui", "telecom", "lists-updater", "tools/detekt-rules", "baselineprofile").flatMap { m ->
            listOf("$m/src/main/kotlin", "$m/src/test/kotlin", "$m/src/testShared/kotlin")
        }.map { file(it) }.filter { it.exists() },
    )
}

dependencies {
    detektPlugins(libs.detekt.formatting)
    detektPlugins(project(":tools:detekt-rules"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    // Parley's own rules are tested before they judge the code.
    dependsOn(":tools:detekt-rules:test")
    jvmTarget = "17"
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(false)
        txt.required.set(false)
        md.required.set(false)
    }
}
tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach { jvmTarget = "17" }

// detekt 1.23 runs its own Kotlin compiler (2.0.x); keep the Kotlin Gradle plugin's newer version out of its classpath.
configurations.matching { it.name == "detekt" || it.name == "detektPlugins" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
    }
}

/*
 * Dependency verification: every artifact Gradle downloads is checked against gradle/verification-metadata.xml
 * (SHA-256). After adding or updating a dependency, regenerate the file and review the diff:
 *   ./gradlew --write-verification-metadata sha256 resolveAllDependencies <the tasks CI runs>
 * This task resolves every configuration of every module (release ones included) so nothing is missed.
 */
tasks.register("resolveAllDependencies") {
    group = "help"
    description = "Resolves every resolvable configuration of every module (for dependency verification metadata)."
    notCompatibleWithConfigurationCache("Walks every project's configurations")
    doLast {
        allprojects.forEach { p ->
            p.configurations.filter { it.isCanBeResolved }.forEach { c ->
                // Some Android configurations can't be resolved on their own (they need a variant's attributes).
                runCatching { c.resolve() }
            }
        }
    }
}
