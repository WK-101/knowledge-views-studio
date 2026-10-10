package app.parley.detekt

import io.github.detekt.parser.KtCompiler
import io.gitlab.arturbosch.detekt.api.Finding
import io.gitlab.arturbosch.detekt.api.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Each of Parley's detekt rules on small sources: what it reports and what it leaves alone. */
class ParleyRulesTest {
    @get:JUnitRule val tmp = TemporaryFolder()
    private val compiler = KtCompiler()

    /** Runs [rule] over [code] as the file at [path] (relative to a project root in a temporary folder). */
    private fun lint(rule: Rule, code: String, path: String = "app/src/main/kotlin/app/parley/ui/Screen.kt"): List<Finding> {
        val root = tmp.root.toPath()
        val text = code.trimIndent()
        val file = root.resolve(path).toFile().apply { parentFile.mkdirs(); writeText(text) }
        rule.visitFile(compiler.createKtFile(text, root, file.toPath()))
        return rule.findings
    }

    @Test fun raw_material_components_are_reported_and_the_kit_is_not() {
        val code = """
            fun Screen() {
                AlertDialog(onDismissRequest = {})
                TopAppBar(title = {})
                ParleyDialog(onDismissRequest = {})
                androidx.compose.material3.Switch(true, null)
                other.AlertDialog()
            }
        """
        val found = lint(DesignSystemComponent(), code).map { it.message }
        assertEquals(found.toString(), 3, found.size)
        assertTrue(found[0].contains("ConfirmDialog"))
        assertTrue(found.any { "SwitchRow" in it })
    }

    @Test fun a_system_toast_is_reported() {
        val code = """
            fun f(c: Context) {
                Toast.makeText(c, "Hi", Toast.LENGTH_SHORT).show()
                android.widget.Toast.makeText(c, "Hi", 0)
                snackbar.makeText()
            }
        """
        assertEquals(2, lint(SystemToast(), code).size)
    }

    @Test fun phone_numbers_outside_its_package_are_reported_but_not_its_import_or_home() {
        val outside = """
            package app.parley.data
            import app.parley.common.PhoneNumbers
            fun same(a: String, b: String) = PhoneNumbers.normalize(a) == PhoneIdentity.key(b)
        """
        assertEquals(1, lint(PhoneNumbersOutsideIdentity(), outside, "core/data/src/main/kotlin/app/parley/data/X.kt").size)
        val home = """
            package app.parley.common
            fun same(a: String) = PhoneNumbers.normalize(a)
        """
        assertTrue(lint(PhoneNumbersOutsideIdentity(), home, "core/common/src/main/kotlin/app/parley/common/X.kt").isEmpty())
    }

    @Test fun run_catching_in_suspend_code_is_reported_and_elsewhere_is_not() {
        val code = """
            suspend fun a() = runCatching { 1 }
            fun b() = runCatching { 2 }
            fun c(scope: CoroutineScope) { scope.launch { runCatching { 3 } } }
            fun d() = listOf(1).map { runCatching { it } }
            fun e() { withContext(Dispatchers.IO, { runCatching { 4 } }) }
        """
        val lines = lint(RunCatchingInSuspend(), code).map { it.location.source.line }
        assertEquals(listOf(1, 3, 5), lines)
    }

    @Test fun a_lock_character_in_ui_text_is_reported_but_not_in_a_comment_or_outside_ui() {
        val lock = PrivateMark.LOCK
        val code = """
            // A comment about the $lock lock
            val a = "$lock " + name
            val b = "plain ${'$'}{f("x")}"
        """
        assertEquals(1, lint(PrivateMark(), code).size)
        assertTrue(lint(PrivateMark(), code, "core/data/src/main/kotlin/app/parley/data/Log.kt").isEmpty())
        assertEquals(1, lint(PrivateMark(), code, "telecom/src/main/kotlin/app/parley/telecom/MissedCallNotifier.kt").size)
    }

    @Test fun a_badge_of_a_lists_own_or_a_list_without_the_shared_one_is_reported() {
        assertEquals(1, lint(PrivateMark(), "@Composable fun PrivateBadge() {}").size)
        val list = "app/src/main/kotlin/app/parley/ui/home/RecentsTab.kt"
        assertEquals(1, lint(PrivateMark(), "fun Row() { Avatar() } // PrivateBadge(", list).size)
        assertTrue(lint(PrivateMark(), "fun Row() { PrivateMarked(true) { Avatar() } }", list).isEmpty())
    }

    @Test fun a_suppress_needs_a_reason_beside_or_above_it() {
        val code = """
            @Suppress("A") // Why.
            fun a() {}

            // Why, above.
            @Composable
            @Suppress("B")
            fun b() {}

            /** Docs alone are no reason. */
            @Suppress("C")
            fun c() {}

            @Suppress(
                "D",
                "E",
            ) // Why, after a long list.
            fun d() {}

            fun e(@Suppress("F") x: Int) {}
        """
        val lines = lint(SuppressWithoutReason(), code).map { it.location.source.line }
        assertEquals(listOf(10, 19), lines)
        assertFalse(SuppressWithoutReason.hasReason("@Suppress(\"X\")\nfun f()", 0, 14))
    }

    @Test fun a_raw_privacy_switch_read_is_reported_but_not_a_write_or_the_privacy_view() {
        val code = """
            fun a(s: AppSettings) = s.hideVault
            fun b(s: AppSettings) = s.duress?.hideVault ?: false
            fun c() = Concealment.hiding
            fun d(s: AppSettings) = s.settings.value.lockScreenCaller
            fun e(s: AppSettings) = s.copy(hideVault = true)
            fun f(c: DataContainer) = c.privacy.memory().privateHidden
        """.trimIndent()
        val lines = lint(RawPrivacySwitch(), code).map { it.location.source.line }
        assertEquals(listOf(1, 2, 3, 4), lines.distinct().sorted())
    }

    @Test fun a_bare_rename_is_reported() {
        val code = """
            fun a(f: File, g: File) = f.renameTo(g)
            fun b(f: File, g: File) = DurableFiles.move(f, g)
        """.trimIndent()
        assertEquals(listOf(1), lint(RawRename(), code).map { it.location.source.line })
    }
}
