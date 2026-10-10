package app.parley.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtAnnotationEntry

/**
 * Every `@Suppress` says why, in a `//` comment on its own line (after the annotation) or on the line just above it
 * (other annotations in between are fine). A suppression without a reason can't be reviewed or retired later.
 */
class SuppressWithoutReason(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        javaClass.simpleName,
        Severity.Maintainability,
        "Give every @Suppress a reason in a // comment on its line or the line above.",
        Debt.FIVE_MINS,
    )

    override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
        super.visitAnnotationEntry(annotationEntry)
        if (annotationEntry.shortName?.asString() !in NAMES) return
        val text = annotationEntry.containingKtFile.text
        if (!hasReason(text, annotationEntry.textRange.startOffset, annotationEntry.textRange.endOffset)) {
            report(CodeSmell(issue, Entity.from(annotationEntry), "@Suppress without a reason: add a // comment saying why."))
        }
    }

    companion object {
        private val NAMES = setOf("Suppress", "SuppressWarnings")

        /** Whether the annotation at [start]..[end] of [text] has a `//` comment after it on its last line, or above it. */
        fun hasReason(text: String, start: Int, end: Int): Boolean {
            val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
            if ("//" in text.substring(end, lineEnd)) return true
            var lineStart = text.lastIndexOf('\n', start - 1) + 1
            while (lineStart > 0) {
                val prevStart = text.lastIndexOf('\n', lineStart - 2) + 1
                val prev = text.substring(prevStart, lineStart - 1).trim()
                when {
                    prev.startsWith("//") -> return true
                    // Annotations stacked above it: the reason may sit above them.
                    prev.startsWith("@") -> lineStart = prevStart
                    else -> return false
                }
            }
            return false
        }
    }
}
