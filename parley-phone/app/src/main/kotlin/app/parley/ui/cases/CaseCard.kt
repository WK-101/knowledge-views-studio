package app.parley.ui.cases

import android.content.Context
import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.calls.MenuMemory
import app.parley.common.calls.MenuStep
import app.parley.common.cases.CaseSummary
import app.parley.ui.Bidi
import app.parley.ui.Destination
import app.parley.ui.ParleyShapes
import app.parley.ui.Spacing
import app.parley.ui.common.Format
import app.parley.ui.history.HistoryRoutes
import kotlinx.coroutines.launch

/**
 * The compact "Case file" card of an organisation (a contact page, a number's page, the pre-call peek): how many calls
 * and the last one, the usual hold time, the menu keys last pressed, the reference numbers and open promises kept.
 * A tap opens the case file. Nothing shows when none is kept for them (or a duress unlock hides it).
 */
@Composable
fun CaseCard(vm: AppViewModel, owner: CaseOwner, open: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val shown = rememberCaseShown(vm, owner)
    if (!shown.shown) return
    val timeline = rememberCaseTimeline(vm, shown.case, owner) ?: return
    val context = LocalContext.current
    val res = LocalResources.current
    val lines = summaryLines(context, res, timeline.summary)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = ParleyShapes.card,
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.l).clickable(role = Role.Button, onClickLabel = stringResource(R.string.case_open)) {
            val id = shown.case?.id
            if (id != null) {
                open(HistoryRoutes.Case(id))
            } else {
                vm.viewModelScope.launch { CaseData.ensure(vm.c, owner, vm.countryIso)?.let { open(HistoryRoutes.Case(it)) } }
            }
        },
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = Spacing.l, vertical = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FolderOpen, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.case_title), style = MaterialTheme.typography.titleSmall)
                lines.forEach { line ->
                    Text(
                        line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The card's lines: the calls and the last one; then the hold time, the menu keys, references and promises. */
internal fun summaryLines(context: Context, res: Resources, s: CaseSummary): List<String> {
    val sep = res.getString(R.string.main_separator)
    val first = s.lastCallAt?.let { last -> res.getQuantityString(R.plurals.case_calls_last, s.calls, s.calls, Format.shortWhen(context, last)) }
        ?: res.getString(R.string.case_no_calls)
    val more = listOfNotNull(
        res.getString(R.string.case_hold_average, Format.duration(s.averageHoldSec)).takeIf { s.heldCalls > 0 },
        res.getString(R.string.case_menu, Bidi.ltr(menuLabel(s.menu))).takeIf { s.menu.isNotEmpty() },
        res.getQuantityString(R.plurals.case_references, s.references, s.references).takeIf { s.references > 0 },
        res.getQuantityString(R.plurals.case_open_promises, s.openPromises, s.openPromises).takeIf { s.openPromises > 0 },
    )
    return listOf(first) + listOfNotNull(more.joinToString(sep).ifEmpty { null })
}

/** "2 › 1 › 4" for the keys "214". */
internal fun menuLabel(keys: String): String = MenuMemory.label(keys.map { MenuStep(it, 0) })
