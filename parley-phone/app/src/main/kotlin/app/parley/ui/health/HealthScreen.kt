package app.parley.ui.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.data.HealthIssue
import app.parley.data.HealthKind
import app.parley.data.HealthScanner
import app.parley.data.db.TemporaryContactEntity
import app.parley.ui.EmptyState
import app.parley.ui.Routes
import app.parley.ui.contact.Section
import kotlinx.coroutines.launch

private val titles = mapOf(
    HealthKind.NO_COUNTRY_CODE to "Numbers without country code",
    HealthKind.TITLE_IS_COMPANY to "Job title copies the company",
    HealthKind.NUMBER_AS_NAME to "Contacts with no name",
    HealthKind.EMPTY to "Empty contacts",
    HealthKind.SHARED_NUMBER to "Same number in several contacts",
    HealthKind.STALE to "Not called in over 2 years",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val calls by vm.c.callLog.calls.collectAsStateWithLifecycle()
    val scanner = remember { HealthScanner(vm.c.appContext) }
    var issues by remember { mutableStateOf<List<HealthIssue>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(contacts, round) { issues = scanner.scan(contacts.orEmpty(), calls.orEmpty(), vm.countryIso) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Contact health check") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        val list = issues
        if (list == null) {
            CircularProgressIndicator(Modifier.padding(p).padding(32.dp))
            return@Scaffold
        }
        if (list.isEmpty()) {
            androidx.compose.foundation.layout.Column(Modifier.padding(p).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                app.parley.ui.people.AccountDiagnosticsSection(vm)
                EmptyState(Icons.Rounded.HealthAndSafety, "All tidy", "No problems found in your contacts.")
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(p)) {
            item { app.parley.ui.people.AccountDiagnosticsSection(vm) }
            titles.forEach { (kind, title) ->
                val group = list.filter { it.kind == kind }
                if (group.isEmpty()) return@forEach
                item { Section("$title (${group.size})") }
                item {
                    when (kind) {
                        HealthKind.NO_COUNTRY_CODE, HealthKind.TITLE_IS_COMPANY -> Button(
                            onClick = { scope.launch { val n = scanner.fix(group); vm.toast("Fixed $n entries"); round++ } },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) { Text("Fix all ${group.size}") }
                        HealthKind.SHARED_NUMBER -> TextButton({ open(Routes.DUPLICATES) }, Modifier.padding(horizontal = 8.dp)) { Text("Review duplicates") }
                        HealthKind.STALE -> TextButton({
                            scope.launch {
                                group.forEach { vm.c.meta.setTemporary(TemporaryContactEntity(it.lookupKey, it.contactId, System.currentTimeMillis() + 30 * 86_400_000L, purgeHistory = false)) }
                                vm.toast("${group.size} contacts will delete themselves in 30 days unless you change it")
                            }
                        }, Modifier.padding(horizontal = 8.dp)) { Text("Auto-delete these in 30 days") }
                        HealthKind.EMPTY -> TextButton({ vm.deleteContacts(group.map { it.contactId }); round++ }, Modifier.padding(horizontal = 8.dp)) { Text("Delete all ${group.size}") }
                        HealthKind.NUMBER_AS_NAME -> Unit
                    }
                }
                group.take(200).forEach { i ->
                    item {
                        ListItem(
                            modifier = Modifier.clickable { open(if (i.kind == HealthKind.NUMBER_AS_NAME) Routes.edit(id = i.contactId) else Routes.contact(i.contactId)) },
                            headlineContent = { Text(i.name) },
                            supportingContent = { Text(i.detail, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                    }
                }
            }
        }
    }
}
