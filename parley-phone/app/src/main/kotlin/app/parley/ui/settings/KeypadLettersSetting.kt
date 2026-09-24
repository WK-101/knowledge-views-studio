package app.parley.ui.settings

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.KeypadLayout
import app.parley.messaging.MyDetailsDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings › Keypad: "Keypad letters" (K6). */
@Composable
fun KeypadLettersRow(vm: AppViewModel, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val store = vm.c.messaging
    val choice by store.keypadLayoutChoice.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    var pickLayout by remember { mutableStateOf(false) }
    val phoneLanguage = remember { store.effectiveLayout(null) }

    LinkRow(
        entry("keypad_letters").title,
        (choice ?: phoneLanguage).label + if (choice == null) " · same as phone language" else "",
        icon,
    ) { pickLayout = true }

    if (pickLayout) {
        // Scripts found in your contacts' names, most common first.
        val suggested = remember(contacts) { KeypadLayout.suggest(contacts.orEmpty().asSequence().map { it.displayName }, minNames = 1) }
        val others = KeypadLayout.entries.filter { it !in suggested }
        AlertDialog(
            onDismissRequest = { pickLayout = false },
            title = { Text("Keypad letters") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    item {
                        Text(
                            "Latin letters are always on the keys. Pick a second alphabet to show under them and to search names with.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    item {
                        LayoutRow("Same as phone language", phoneLanguage.label, choice == null) { store.setKeypadLayout(null); pickLayout = false }
                    }
                    if (suggested.isNotEmpty()) item { Text("Suggested from your contacts", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    items(suggested) { l -> LayoutRow(l.label, null, choice == l) { store.setKeypadLayout(l); pickLayout = false } }
                    if (suggested.isNotEmpty()) item { Text("All", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    items(others) { l -> LayoutRow(l.label, null, choice == l) { store.setKeypadLayout(l); pickLayout = false } }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ pickLayout = false }) { Text("Cancel") } },
        )
    }
}

/** Settings › Messaging: "My card" (I2), which replaced "My details" and still fills in "Send my details". */
@Composable
fun MyDetailsRow(vm: AppViewModel, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val details by vm.c.messaging.myDetails.collectAsStateWithLifecycle()
    LinkRow(
        entry("my_details").title,
        listOf(details.name, details.number).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { entry("my_details").summary },
        icon,
    ) { vm.navigate(app.parley.NavEvent.Route(app.parley.ui.people.PeopleRoutes.ME)) }
}

@Composable
private fun LayoutRow(title: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
        leadingContent = { RadioButton(selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick),
    )
}

