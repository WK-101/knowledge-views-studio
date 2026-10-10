package app.parley.detekt

import io.github.detekt.psi.absolutePath
import io.github.detekt.psi.toUnifiedString
import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Every list marks a private contact the way Contacts does: core/ui's `PrivateBadge` on the photo (directly or through
 * `PrivateMarked`), never a lock character before the name or a badge of a list's own. So, in the app's and the call
 * screen's UI code (`ui` and `calls` packages and notifiers, where what people read is written):
 * - no string literal holds the lock character (comments may talk about it);
 * - no function named `PrivateBadge` is declared outside core/ui;
 * - each of the [lists] (file names) calls `PrivateBadge(` or `PrivateMarked(`.
 * The app's string resources are checked by `PrivateMarkTest`, as detekt reads Kotlin only.
 */
class PrivateMark(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        javaClass.simpleName,
        Severity.Defect,
        "Mark private contacts with core/ui's PrivateBadge or PrivateMarked, not a lock character or a badge of a list's own.",
        Debt.FIVE_MINS,
    )

    private val lists: List<String> by lazy { valueOrDefault("lists", DEFAULT_LISTS) }

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        val path = file.absolutePath().toUnifiedString()
        val name = path.substringAfterLast('/')
        if (isUi(path, name)) {
            file.collectDescendantsOfType<KtStringTemplateExpression>()
                .filter { it.parent !is KtStringTemplateExpression && LOCK in it.text }
                .forEach { report(CodeSmell(issue, Entity.from(it), "A lock character marks a private contact: use PrivateBadge or PrivateMarked.")) }
        }
        file.collectDescendantsOfType<KtNamedFunction>().filter { it.name == "PrivateBadge" }.forEach {
            report(CodeSmell(issue, Entity.from(it), "A private badge of its own: use core/ui's PrivateBadge."))
        }
        if (name in lists) {
            val calls = file.collectDescendantsOfType<KtCallExpression>().any { it.calleeExpression?.text in BADGES }
            val message = "$name lists private contacts without the shared PrivateBadge or PrivateMarked."
            if (!calls) report(CodeSmell(issue, Entity.atPackageOrFirstDecl(file), message))
        }
    }

    private fun isUi(path: String, name: String) = "/ui/" in path || "/calls/" in path || name.endsWith("Notifier.kt")

    companion object {
        const val LOCK = "🔒"
        private val BADGES = setOf("PrivateBadge", "PrivateMarked")

        /** The lists that show private contacts or callers. */
        val DEFAULT_LISTS = listOf(
            "ContactsTab.kt", "FavoritesTab.kt", "RecentsTab.kt", "CircleTab.kt", "ToCallScreen.kt", "RecentsLegend.kt", "KeypadTab.kt",
        )
    }
}
