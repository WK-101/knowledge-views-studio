package app.parley.common.perf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The hand-written start-up profile (app/src/main/baseline-prof.txt) names code that exists, and keeps covering what
 * every start and every ring runs: a rule left behind by a rename compiles nothing ahead of time, silently.
 */
class BaselineProfileRulesTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "core").isDirectory }

    private val rules: List<String> by lazy {
        File(root, "app/src/main/baseline-prof.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    /** Every class Parley declares at the top of a file, and each file's own class (`NameKt`), as `app.parley.…`. */
    private val declared: Set<String> by lazy {
        val decl = Regex("""^(?:(?:public|internal|private|abstract|open|sealed|data|enum|annotation|value|fun)\s+)*(?:class|object|interface)\s+(\w+)""", RegexOption.MULTILINE)
        listOf("app", "core", "telecom").map { File(root, it) }.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path && "/build/" !in it.path }.toList()
        }.flatMap { f ->
            val text = f.readText()
            val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: return@flatMap emptyList()
            decl.findAll(text).map { "$pkg.${it.groupValues[1]}" }.toList() + "$pkg.${f.nameWithoutExtension}Kt"
        }.toSet()
    }

    /** The class part of a rule, as a prefix of names (`app.parley.data.calls.` for the data/calls package rule). */
    private fun prefixOf(rule: String): String {
        val body = rule.dropWhile { it in "HSPL" }.substringBefore("->").removePrefix("L")
        val exact = body.endsWith(";")
        val path = body.removeSuffix(";").removeSuffix("**").replace('/', '.')
        return if (exact) "$path\u0000" else path
    }

    @Test fun every_rule_names_code_that_exists() {
        val names = declared.map { "$it\u0000" }
        val dead = rules.filter { r ->
            val p = prefixOf(r)
            names.none { it.startsWith(p) }
        }
        assertTrue("Rules that match no class: $dead", dead.isEmpty())
    }

    @Test fun every_start_and_ring_path_is_covered() {
        val needed = listOf(
            "app/parley/data/calls/**", "app/parley/data/memory/**", "app/parley/data/archive/**", "app/parley/data/situations/**",
            "app/parley/data/cases/**", "app/parley/data/vault/**", "app/parley/data/security/**", "app/parley/calls/**",
            "app/parley/data/StatePrefs;", "app/parley/data/sync/shared/FamilyShieldStore**", "app/parley/ui/recall/**",
            "app/parley/common/recall/**", "app/parley/situations/SituationTriggers**", "app/parley/data/circle/AgendaStore**",
        )
        val bodies = rules.map { it.dropWhile { c -> c in "HSPL" }.substringBefore("->") }.toSet()
        val missing = needed - bodies
        assertTrue("Not in the profile: $missing", missing.isEmpty())
    }
}
