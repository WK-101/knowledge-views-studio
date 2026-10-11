package app.parley.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.ConfirmDialog
import app.parley.ui.StrengthMeter
import app.parley.common.security.PassphraseStrength

/** Set or change the backup passphrase, with a strength meter: it must stand up to offline guessing. */
@Composable
internal fun PassphraseDialog(change: Boolean, onDismiss: () -> Unit, onSave: (String?, String) -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val estimate = remember(new) { PassphraseStrength.estimate(new) }
    // Every backup carries the key wrapped under this passphrase, so it must stand up to offline guessing.
    val strongEnough = PassphraseStrength.acceptableForBackup(new)
    val ok = strongEnough && new == confirm && (!change || old.isNotEmpty())
    ConfirmDialog(
        title = if (change) stringResource(R.string.bkp_change_pass_title) else stringResource(R.string.bkp_pass_title),
        text = null,
        confirmLabel = stringResource(R.string.action_save),
        onConfirm = { onSave(old.takeIf { change }, new) },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.dc_cancel),
        confirmEnabled = ok,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (change) PassField(stringResource(R.string.bkp_current_pass), old) { old = it }
                PassField(stringResource(R.string.bkp_new_pass), new) { new = it }
                if (new.isNotEmpty()) {
                    StrengthMeter(estimate.score, stringResource(strengthLabel(estimate.score)), strengthHint(estimate.hint)?.let { stringResource(it) })
                }
                PassField(stringResource(R.string.bkp_repeat), confirm) { confirm = it }
                val hint = if (new.isNotEmpty() && !strongEnough) R.string.bkp_strength_needed else R.string.bkp_pass_hint
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

internal fun strengthLabel(score: Int) = when (score) {
    0 -> R.string.bkp_strength_0
    1 -> R.string.bkp_strength_1
    2 -> R.string.bkp_strength_2
    3 -> R.string.bkp_strength_3
    else -> R.string.bkp_strength_4
}

internal fun strengthHint(h: PassphraseStrength.Hint): Int? = when (h) {
    PassphraseStrength.Hint.NONE -> null
    PassphraseStrength.Hint.TOO_SHORT -> R.string.bkp_strength_short
    PassphraseStrength.Hint.COMMON -> R.string.bkp_strength_common
    PassphraseStrength.Hint.KEYBOARD -> R.string.bkp_strength_keyboard
    PassphraseStrength.Hint.SEQUENCE -> R.string.bkp_strength_sequence
    PassphraseStrength.Hint.REPEAT -> R.string.bkp_strength_repeat
    PassphraseStrength.Hint.DATE -> R.string.bkp_strength_date
    PassphraseStrength.Hint.ADD_WORDS -> R.string.bkp_strength_words
}

@Composable
fun PassField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}
