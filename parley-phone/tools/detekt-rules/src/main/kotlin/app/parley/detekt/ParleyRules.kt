package app.parley.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression

/** Parley's rules; configured under `parley:` in config/detekt/detekt.yml. */
class ParleyRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "parley"

    override fun instance(config: Config): RuleSet = RuleSet(ruleSetId, listOf(DesignSystemComponent(config), SystemToast(config)))
}

/**
 * Screens use the shared components of core/ui instead of raw Material ones, so top bars, dialogs, sheets and
 * corner radii look and behave the same everywhere (heading semantics, Back label, destructive colours, the
 * theme's shape scale). core/ui itself is excluded in the configuration: it is where the raw ones are wrapped.
 */
class DesignSystemComponent(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        javaClass.simpleName,
        Severity.Style,
        "Use the shared component from core/ui instead of the raw Material one.",
        Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeExpression?.text ?: return
        val instead = REPLACEMENTS[name] ?: return
        // A call on something else (`foo.AlertDialog(…)`) is not Material's composable.
        val parent = expression.parent
        if (parent is KtDotQualifiedExpression && parent.selectorExpression == expression && !parent.receiverExpression.text.startsWith("androidx.")) return
        report(CodeSmell(issue, Entity.from(expression), "$name( outside core/ui: use $instead."))
    }

    private companion object {
        val REPLACEMENTS = mapOf(
            "TopAppBar" to "ParleyTopBar",
            "LargeTopAppBar" to "ParleyTopBar(large = true)",
            "MediumTopAppBar" to "ParleyTopBar",
            "CenterAlignedTopAppBar" to "ParleyTopBar",
            "AlertDialog" to "ConfirmDialog, InfoDialog or ParleyDialog",
            "BasicAlertDialog" to "ParleyDialog",
            "ModalBottomSheet" to "ParleySheet",
            "RoundedCornerShape" to "ParleyShapes (or animatedCorners for a radius computed at run time)",
        )
    }
}

/**
 * Inside Parley's own screens a message goes to the app's snackbar (`rememberShowMessage`, `vm.toast`), which
 * sits above the navigation bar and moves the floating button. A system toast is only for surfaces where no
 * Parley screen shows (the call screen, sheets over other apps, services): those use core/ui's `systemMessage`,
 * or suppress this rule with a reason.
 */
class SystemToast(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        javaClass.simpleName,
        Severity.Style,
        "Show messages in Parley's screens with the snackbar, not a system toast.",
        Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != "makeText") return
        val parent = expression.parent as? KtDotQualifiedExpression ?: return
        if (parent.receiverExpression.text.endsWith("Toast")) {
            report(CodeSmell(issue, Entity.from(expression), "Toast.makeText outside core/ui: use rememberShowMessage() or systemMessage()."))
        }
    }
}
