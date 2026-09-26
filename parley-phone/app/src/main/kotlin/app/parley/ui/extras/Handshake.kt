package app.parley.ui.extras

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.circle.InteractionType
import app.parley.common.extras.Handshake
import app.parley.common.people.MeCard
import app.parley.common.people.MeCards
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * X5 handshake: a contact received by QR remembers where and when you met ("Met at the conference on 25 Sep"), as
 * a MEET entry in the Circle timeline and, if chosen, a line in the contact's note. "Swap" shows your own card right
 * after theirs arrives, so both phones end up with each other's details.
 */
object HandshakeInbox {
    /** A received contact on its way through the editor: logged once the editor saves it ([onSaved]). */
    data class Pending(val line: String, val time: Long, val nonce: String)

    @Volatile
    var pending: Pending? = null

    /** The editor saved contact [savedId] (null or ≤ 0: cancelled or private). Logs the meeting for a pending handshake. */
    fun onSaved(vm: AppViewModel, savedId: Long?) {
        val p = pending ?: return
        pending = null
        if (savedId == null || savedId <= 0) return
        vm.c.scope.launch {
            val key = runCatching { vm.c.contacts.lookupKeyOf(savedId) }.getOrNull() ?: return@launch
            runCatching { vm.c.circle.interactions.log(key, savedId, InteractionType.MEET, null, p.time, p.line, Handshake.meetKey(p.nonce)) }
        }
    }

    /** "Met at [place] on 25 Sep" in the app's language (without a place: "Met on 25 Sep"). */
    fun line(res: Resources, place: String, time: Long): String {
        val locale = res.configuration.locales[0] ?: Locale.getDefault()
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "d MMM yyyy")
        val date = java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(time))
        val p = Handshake.cleanPlace(place)
        return if (p.isEmpty()) res.getString(R.string.x_hs_met_on, date) else res.getString(R.string.x_hs_met_at, p, date)
    }
}

/** The receive screen's extra fields: where you met, whether the note gets the line, and swap. */
@Composable
fun HandshakeFields(vm: AppViewModel, place: String, onPlace: (String) -> Unit, toNote: Boolean, onToNote: (Boolean) -> Unit) {
    val swap by vm.c.extras.handshakeSwap.collectAsStateWithLifecycle()
    Column(Modifier.padding(top = 12.dp)) {
        OutlinedTextField(
            place, onPlace, singleLine = true, label = { Text(stringResource(R.string.x_hs_place)) },
            placeholder = { Text(stringResource(R.string.x_hs_place_hint)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.x_hs_place_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        CheckLine(stringResource(R.string.x_hs_to_note), toNote, onToNote)
        CheckLine(stringResource(R.string.x_hs_swap), swap) { vm.c.extras.setHandshakeSwap(it) }
    }
}

@Composable
private fun CheckLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** X5 "Swap": your own card as a QR code (the Me card's dialog), shown right after theirs arrived. */
@Composable
fun MyCardQrDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val own by vm.c.people.me.card.collectAsStateWithLifecycle()
    val profile by produceState<MeCard?>(null) { value = runCatching { vm.c.people.me.profile() }.getOrNull() }
    val merged = MeCards.merge(own, profile)
    if (merged.isEmpty) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.me_title)) },
            text = { Text(stringResource(R.string.x_hs_no_card)) },
            confirmButton = {
                androidx.compose.material3.TextButton({ onDismiss(); vm.navigate(app.parley.NavEvent.Route(app.parley.ui.people.PeopleRoutes.ME)) }) {
                    Text(stringResource(R.string.x_hs_make_card))
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
        )
    } else {
        app.parley.ui.people.MeQrDialog(merged, onDismiss)
    }
}
