package app.parley.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * Every ⋮ is core/ui's `OverflowMenu` (or `OverflowButton` for one that opens a sheet): one icon, one spoken name in
 * every top bar ("More options"), the row's name on a row ("More for Ana"), and items that set a destructive one
 * apart. A hand-built ⋮ (an IconButton with the MoreVert icon and its own DropdownMenu) is found by the icon: any use
 * of `MoreVert` outside core/ui, which is excluded in the configuration.
 */
class HandBuiltOverflow(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        javaClass.simpleName,
        Severity.Style,
        "Build a ⋮ with OverflowMenu or OverflowButton from core/ui, not an IconButton with the MoreVert icon.",
        Debt.FIVE_MINS,
    )

    override fun visitReferenceExpression(expression: KtReferenceExpression) {
        super.visitReferenceExpression(expression)
        if (expression !is KtNameReferenceExpression || expression.getReferencedName() != "MoreVert") return
        if (expression.getStrictParentOfType<KtImportDirective>() != null) return
        report(CodeSmell(issue, Entity.from(expression), "MoreVert outside core/ui: use OverflowMenu (or OverflowButton)."))
    }
}

/**
 * A part that opens in place has core/ui's `ExpandableHeader` (heading, "Expanded"/"Collapsed", the turning
 * `ExpandChevron`) and `ExpandableBody` (the theme's springs). A hand-built one is found by its chevron: one that swaps
 * to `ExpandLess` when open, or an `ExpandMore` turned by hand (`rotate`, `rotationZ`). A plain `ExpandMore` stays
 * allowed: a drop-down field's arrow doesn't open anything in place.
 */
class HandBuiltExpandable(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        javaClass.simpleName,
        Severity.Style,
        "Build a part that opens in place with ExpandableHeader/ExpandChevron and ExpandableBody from core/ui.",
        Debt.FIVE_MINS,
    )

    override fun visitReferenceExpression(expression: KtReferenceExpression) {
        super.visitReferenceExpression(expression)
        if (expression !is KtNameReferenceExpression) return
        if (expression.getStrictParentOfType<KtImportDirective>() != null) return
        val handBuilt = when (expression.getReferencedName()) {
            "ExpandLess" -> true
            "ExpandMore" -> expression.getStrictParentOfType<KtCallExpression>()?.text?.let { TURNED.containsMatchIn(it) } == true
            else -> false
        }
        if (handBuilt) {
            report(CodeSmell(issue, Entity.from(expression), "A hand-built chevron outside core/ui: use ExpandableHeader or ExpandChevron."))
        }
    }

    private companion object {
        val TURNED = Regex("""\brotat(e|ionZ)\b""")
    }
}
