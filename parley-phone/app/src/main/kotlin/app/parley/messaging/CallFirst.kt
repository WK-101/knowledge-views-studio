package app.parley.messaging

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.ui.Bidi
import app.parley.ui.CallColors

/**
 * C2: the direct Call shown first on every "Message on…" / number-action surface, so a number found in Recents, a
 * notification, shared text or the tile can be called as easily as messaged. [onClick] goes through Parley's normal
 * call path (CallGate: dial guard, allowance, confirm, SIM choice).
 */
@Composable
fun CallFirstButton(number: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val description = stringResource(R.string.v33_call_number, Bidi.ltr(number))
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).heightIn(min = 56.dp)
            .semantics { contentDescription = description },
        colors = ButtonDefaults.buttonColors(containerColor = CallColors.Accept, contentColor = Color.White),
    ) {
        Icon(Icons.Rounded.Call, null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.main_call))
    }
}
