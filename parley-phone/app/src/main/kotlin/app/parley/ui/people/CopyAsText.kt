package app.parley.ui.people

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.parley.common.ContactSummary
import app.parley.common.people.Reports
import app.parley.ui.common.Format

/**
 * U11: "Copy as text" for selected contacts: names, numbers (with their type) and e-mail addresses as plain text.
 * The clip is marked sensitive, so Android 13+ keeps it out of the clipboard preview and keyboard suggestions.
 */
@Composable
fun CopyAsTextMenuItem(chosen: List<ContactSummary>, close: () -> Unit) {
    val context = LocalContext.current
    DropdownMenuItem({ Text("Copy as text") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = {
        close()
        val text = Reports.contactsAsText(
            chosen.map { c ->
                Reports.TextContact(c.displayName, c.phones.map { it.number to Format.phoneType(context.resources, it.type, it.label) }, c.emails)
            },
        )
        val clip = ClipData.newPlainText("contacts", text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        } else {
            // The same flag under its literal name, which some keyboards and clipboard tools also honour before Android 13.
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(context, if (chosen.size == 1) "Contact copied" else "${chosen.size} contacts copied", Toast.LENGTH_SHORT).show()
    })
}
