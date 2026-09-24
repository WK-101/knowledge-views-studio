plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

/*
 * L1: Android lint can't see text written into Compose code, so this task looks for the usual shapes of it
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
            Regex("""\b(?:contentDescription|onClickLabel|stateDescription|label)\s*=\s*$lit"""),
            Regex("""CustomAccessibilityAction\(\s*$lit"""),
            Regex("""Toast\.makeText\([^,]+,\s*$lit"""),
            Regex("""\.set(?:ContentTitle|ContentText|SubText|SummaryText|BigContentTitle)\(\s*$lit"""),
            Regex("""\.addAction\(\s*0\s*,\s*$lit"""),
            Regex("""\b(?:toast|showMessage)\(\s*$lit"""),
        )
        val hits = sources.asFileTree.matching { include("**/*.kt") }.files.sorted().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || line.contains("l10n-ok") || patterns.none { it.containsMatchIn(code) }) null
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

subprojects {
    tasks.matching { it.name.startsWith("lint") && it.name != "lintFix" }.configureEach { dependsOn(rootProject.tasks.named("checkHardcodedText")) }
}
