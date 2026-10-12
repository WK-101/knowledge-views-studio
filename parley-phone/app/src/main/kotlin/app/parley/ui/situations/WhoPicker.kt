package app.parley.ui.situations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.LabelRefs
import app.parley.common.catching
import app.parley.common.who.Who
import app.parley.common.who.WhoChoice
import app.parley.common.who.WhoPlace
import app.parley.common.who.WhoRules
import app.parley.ui.MenuRow
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The labels on this phone by title (every account's, as off hours and Situations match them) with a group row for
 * each, so a Situation can follow a rename made in another app. Empty while they are read or when they can't be.
 */
@Composable
fun rememberLabelRows(vm: AppViewModel): Map<String, Long> {
    val rows by produceState(emptyMap<String, Long>()) {
        value = withContext(Dispatchers.IO) {
            catching { vm.c.contacts.groups().groupBy { LabelRefs.key(it.title) }.mapValues { (_, g) -> g.minOf { it.id } } }.getOrDefault(emptyMap())
        }
    }
    return rows
}

/**
 * The one "who" picker: the same choices in the same order wherever Parley asks who (a Situation's "Who may ring",
 * off hours, auto-answer, Your car): [none] first when the place has a "leave it" answer ("As it is", "Nobody"), then
 * Everyone · Contacts · Favourites · Your Circle · A label… as [place] allows. A label is chosen in a dialog of its
 * own, so the menu never grows past seven items however many labels there are. Where it decides who rings, the row
 * says plainly that Android's own Do Not Disturb follows Favourites only.
 */
@Composable
fun WhoRow(
    title: String,
    sub: String?,
    icon: ImageVector,
    who: Who?,
    place: WhoPlace,
    labels: Collection<String>,
    none: String? = null,
    onPick: (Who?) -> Unit,
) {
    val choices = WhoRules.choicesFor(place)
    val labelName = who?.label?.takeIf { who.choice == WhoChoice.LABEL && it.isNotBlank() }
    val options = listOfNotNull(none) + choices.map { c -> whoName(Who(c, labelName.takeIf { c == WhoChoice.LABEL })) }
    val offset = if (none != null) 1 else 0
    val selected = who?.let { w -> choices.indexOf(w.choice).takeIf { it >= 0 }?.plus(offset) } ?: 0
    // Only where it decides who rings.
    val note = stringResource(R.string.who_dnd_note).takeIf { place == WhoPlace.SITUATION || place == WhoPlace.OFF_HOURS }
    val text = listOfNotNull(sub, note).joinToString("\n").ifEmpty { null }
    var picking by rememberSaveable { mutableStateOf(false) }
    MenuRow(title, options, selected, icon, text) { i ->
        val c = choices.getOrNull(i - offset)
        when {
            c == null -> onPick(null)
            c == WhoChoice.LABEL -> picking = true
            else -> onPick(Who(c))
        }
    }
    if (picking) LabelPickDialog(labels, labelName, onDismiss = { picking = false }) { t ->
        picking = false
        onPick(Who.label(t))
    }
}

/** The name of [who] as the picker shows it: "Contacts", "Your Circle", "Label: Family"… */
@Composable
fun whoName(who: Who): String = when (who.choice) {
    WhoChoice.EVERYONE -> stringResource(R.string.label_everyone)
    WhoChoice.CONTACTS -> stringResource(R.string.label_contacts)
    WhoChoice.FAVOURITES -> stringResource(R.string.label_favourites)
    WhoChoice.CIRCLE -> stringResource(R.string.who_circle)
    WhoChoice.LABEL -> who.label?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.who_label_named, it) } ?: stringResource(R.string.who_label_pick)
}

/** "Choose a label": every label once (by title), the chosen one marked. */
@Composable
private fun LabelPickDialog(labels: Collection<String>, current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    // A label chosen earlier is offered even while the labels are read (or if it went).
    val all = (listOfNotNull(current) + labels).distinctBy(LabelRefs::key)
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.who_pick_label_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (all.isEmpty()) Text(stringResource(R.string.who_no_labels), style = MaterialTheme.typography.bodyMedium)
                all.forEach { t ->
                    val on = current?.let(LabelRefs::key) == LabelRefs.key(t)
                    ParleyListItem(
                        headlineContent = { Text(t) },
                        leadingContent = { RadioButton(on, onClick = null) },
                        modifier = Modifier.semantics { role = Role.RadioButton; selected = on }.clickable { onPick(t) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )
}
