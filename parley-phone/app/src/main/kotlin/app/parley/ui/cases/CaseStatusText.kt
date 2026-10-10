package app.parley.ui.cases

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.parley.R
import app.parley.common.cases.CaseStatus
import app.parley.ui.Spacing

/** The words for where a case stands ([CaseStatus]), on its page, in the list and in the PDF. */
object CaseStatusText {
    @StringRes fun of(s: CaseStatus): Int = when (s) {
        CaseStatus.OPEN -> R.string.case_status_open
        CaseStatus.WAITING -> R.string.case_status_waiting
        CaseStatus.RESOLVED -> R.string.case_status_resolved
    }

    /** "Status: Waiting for them, since 4 Oct 2026" ([since] null: never set, so no date). */
    fun line(res: Resources, s: CaseStatus, since: String?): String {
        val word = res.getString(of(s))
        return if (since == null) res.getString(R.string.case_status_line, word) else res.getString(R.string.case_status_line_since, word, since)
    }
}

/** Open · Waiting for them · Resolved: one tap sets it. Each button is a radio for TalkBack (selected, "1 of 3"). */
@Composable
fun CaseStatusChooser(status: CaseStatus, modifier: Modifier = Modifier, onPick: (CaseStatus) -> Unit) {
    val all = CaseStatus.entries
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        all.forEachIndexed { i, s ->
            SegmentedButton(
                selected = s == status,
                onClick = { if (s != status) onPick(s) },
                shape = SegmentedButtonDefaults.itemShape(i, all.size),
            ) { Text(stringResource(CaseStatusText.of(s)), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}
