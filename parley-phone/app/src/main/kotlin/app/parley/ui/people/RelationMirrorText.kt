package app.parley.ui.people

import android.content.res.Resources
import app.parley.R
import app.parley.common.people.RelationMirror
import app.parley.common.people.RelationTypes
import app.parley.data.people.RelationMirrors

/** What saving did on the other contacts, in one line: "Also added to Ana: Mother → Child". */
object RelationMirrorText {
    fun label(res: Resources, row: RelationMirror.Row): String =
        row.typeKey?.let(RelationTypes::byKey)?.let { RelationText.label(res, it) } ?: row.label.orEmpty()

    /** The snackbar text, or null when nothing happened worth saying. */
    fun summary(res: Resources, report: RelationMirrors.Report): String? {
        val done = report.done
        if (done.size > 1) return res.getQuantityString(R.plurals.rel_mirror_several, done.size, done.size)
        done.singleOrNull()?.let { d ->
            val here = d.relation?.let { label(res, it) }
            return when (val s = d.step) {
                is RelationMirror.Step.Add -> if (here != null) {
                    res.getString(R.string.rel_mirror_added, d.targetName, here, label(res, s.row))
                } else {
                    res.getString(R.string.rel_mirror_added_plain, d.targetName)
                }
                is RelationMirror.Step.Change -> if (here != null) {
                    res.getString(R.string.rel_mirror_changed, d.targetName, here, label(res, s.row))
                } else {
                    res.getString(R.string.rel_mirror_changed_plain, d.targetName)
                }
                is RelationMirror.Step.Remove -> res.getString(R.string.rel_mirror_removed, d.targetName)
            }
        }
        val skipped = report.skipped.firstOrNull() ?: return null
        return res.getString(
            when (skipped.reason) {
                RelationMirrors.Reason.READ_ONLY -> R.string.rel_mirror_read_only
                RelationMirrors.Reason.CHANGED -> R.string.rel_mirror_changed_elsewhere
            },
            skipped.targetName,
        )
    }
}
