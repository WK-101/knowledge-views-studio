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
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/** Parley's rules; configured under `parley:` in config/detekt/detekt.yml. */
class ParleyRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "parley"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(DesignSystemComponent(config), SystemToast(config), PhoneNumbersOutsideIdentity(config), RunCatchingInSuspend(config)),
    )
}

/**
 * Screens use the shared components of core/ui instead of raw Material ones, so top bars, dialogs, sheets, rows and
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
            // Rows follow the list density and the kit's colours; a switch row is one TalkBack stop.
            "ListItem" to "ParleyListItem, PersonRow, SwitchRow, LinkRow or InfoRow",
            "Switch" to "SwitchRow",
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

/**
 * Phone numbers have one identity path: `PhoneIdentity` (libphonenumber first, the old heuristic only as its
 * fallback). `PhoneNumbers` is the machinery behind it, internal to core/common; code outside its package that reached
 * for it directly could decide "same line" differently from every other feature.
 */
class PhoneNumbersOutsideIdentity(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        javaClass.simpleName,
        Severity.Defect,
        "Compare, key and convert phone numbers through PhoneIdentity, not PhoneNumbers.",
        Debt.FIVE_MINS,
    )

    override fun visitReferenceExpression(expression: KtReferenceExpression) {
        super.visitReferenceExpression(expression)
        if (expression !is KtNameReferenceExpression || expression.getReferencedName() != "PhoneNumbers") return
        if (expression.containingKtFile.packageFqName.asString() == HOME_PACKAGE) return
        // The import alone is reported through its uses.
        if (expression.getStrictParentOfType<KtImportDirective>() != null) return
        report(CodeSmell(issue, Entity.from(expression), "PhoneNumbers outside $HOME_PACKAGE: use PhoneIdentity."))
    }

    private companion object {
        const val HOME_PACKAGE = "app.parley.common"
    }
}

/**
 * `runCatching` catches everything, CancellationException included, so in coroutine code a cancelled job carries on
 * as if the work had merely failed. Inside a suspend function or a coroutine builder's block, use core/common's
 * `catching {}`, which rethrows cancellation. Found by position, without types: a `runCatching` lexically inside a
 * `suspend fun`, or inside the block given to launch, async, withContext and the like.
 *
 * New code only: the sites written before the rule are in config/detekt/baseline.xml (several hundred, screening and
 * upkeep among them) and are replaced over time, so this guards against new ones rather than vouching for the old.
 */
class RunCatchingInSuspend(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        javaClass.simpleName,
        Severity.Defect,
        "runCatching in suspend code swallows cancellation; use catching {} from core/common.",
        Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != "runCatching") return
        if (inSuspendCode(expression)) {
            report(CodeSmell(issue, Entity.from(expression), "runCatching in suspend code: use catching {}, which rethrows cancellation."))
        }
    }

    private fun inSuspendCode(start: KtCallExpression): Boolean {
        var node = start.parent
        while (node != null) {
            when (node) {
                is KtNamedFunction -> return node.hasModifier(KtTokens.SUSPEND_KEYWORD)
                is KtLambdaExpression -> if (builderOf(node) in BUILDERS) return true
            }
            node = node.parent
        }
        return false
    }

    /** The name of the call [lambda] is passed to (as a trailing or a regular argument), or null. */
    private fun builderOf(lambda: KtLambdaExpression): String? {
        val argument = lambda.parent as? KtValueArgument ?: return null
        val call = (if (argument is KtLambdaArgument) argument.parent else argument.parent?.parent) as? KtCallExpression ?: return null
        return call.calleeExpression?.text
    }

    private companion object {
        val BUILDERS = setOf(
            "launch", "async", "withContext", "coroutineScope", "supervisorScope", "withTimeout", "withTimeoutOrNull",
            "LaunchedEffect", "produceState", "flow", "channelFlow", "callbackFlow", "runInterruptible",
        )
    }
}
