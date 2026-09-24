package app.parley.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.parley.data.DialWarning

/**
 * The shared "think before you dial" sheet (B10, B11). Any feature that wants the user to confirm an
 * outgoing call (premium lines, one-ring scams, quotas, limits…) passes its [warnings] here.
 * Get warnings for a number with `container.dialGuard.check(number)`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialGuardSheet(who: String, warnings: List<DialWarning>, onCall: () -> Unit, onCancel: () -> Unit) {
    val severe = warnings.any { it.severe }
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Call $who?", style = MaterialTheme.typography.titleLarge)
            warnings.forEach { w ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        if (w.severe) Icons.Rounded.Warning else Icons.Rounded.Info, null,
                        tint = if (w.severe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(end = 12.dp, top = 2.dp),
                    )
                    Column {
                        Text(w.title, style = MaterialTheme.typography.titleSmall)
                        Text(w.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // The safe choice is the prominent one when the risk is real.
                if (severe) {
                    Button(onCancel, Modifier.weight(1f)) { Text("Don't call") }
                    OutlinedButton(onCall, Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Call anyway") }
                } else {
                    OutlinedButton(onCancel, Modifier.weight(1f)) { Text("Cancel") }
                    Button(onCall, Modifier.weight(1f)) { Text("Call") }
                }
            }
        }
    }
}
