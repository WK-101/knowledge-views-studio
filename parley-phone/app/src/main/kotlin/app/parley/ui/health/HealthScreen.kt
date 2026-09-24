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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.data.HealthIssue
import app.parley.data.HealthKind
import app.parley.data.HealthScanner
import androidx.compose.foundation.layout.heightIn
import app.parley.ui.EmptyState
import app.parley.ui.Routes
import app.parley.ui.contact.Section
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R

private val titles = mapOf(
    HealthKind.NO_COUNTRY_CODE to R.string.health_no_country,
    HealthKind.TITLE_IS_COMPANY to R.string.health_title_company,
    HealthKind.NUMBER_AS_NAME to R.string.health_no_name,
    HealthKind.EMPTY to R.string.health_empty,
    HealthKind.SHARED_NUMBER to R.string.health_shared,
    HealthKind.STALE to R.string.health_stale,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val calls by vm.c.callLog.calls.collectAsStateWithLifecycle()
    val scanner = remember { HealthScanner(vm.c.appContext) }
    var issues by remember { mutableStateOf<List<HealthIssue>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(contacts, round) { issues = scanner.scan(contacts.orEmpty(), calls.orEmpty(), vm.countryIso) }
    var confirmStale by remember { mutableStateOf<List<Triple<HealthIssue, String, String>>?>(null) }
    confirmStale?.let { list ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmStale = null },
            title = { Text(pluralStringResource(R.plurals.health_stale_confirm_title, list.size, list.size)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(stringResource(R.string.health_stale_confirm_text))
                    LazyColumn(Modifier.padding(top = 8.dp).heightIn(max = 320.dp)) {
                        items(list.size) { k ->
                            val (_, name, where) = list[k]
                            ListItem(headlineContent = { Text(name) }, supportingContent = { Text(where) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    confirmStale = null
                    scope.launch {
                        list.forEach { (i, _, _) -> vm.c.temporaries.mark(i.contactId, 30, purgeHistory = false) }
                        vm.toast(context.resources.getQuantityString(R.plurals.health_stale_done, list.size, list.size))
                    }
                }) { Text(stringResource(R.string.health_delete_in_30)) }
            },
            dismissButton = { TextButton({ confirmStale = null }) { Text(stringResource(R.string.dc_cancel)) } },
        )
    }

    // U7: scroll-linked top-bar tint.
    val barTint = androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(barTint.nestedScrollConnection), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.health_title)) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } }, scrollBehavior = barTint)
    }) { p ->
        val list = issues
        if (list == null) {
            CircularProgressIndicator(Modifier.padding(p).padding(32.dp))
            return@Scaffold
        }
        if (list.isEmpty()) {
            androidx.compose.foundation.layout.Column(Modifier.padding(p).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                app.parley.ui.people.AccountDiagnosticsSection(vm)
                EmptyState(Icons.Rounded.HealthAndSafety, stringResource(R.string.health_all_tidy), stringResource(R.string.health_all_tidy_text))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(p)) {
            item { app.parley.ui.people.AccountDiagnosticsSection(vm) }
            titles.forEach { (kind, title) ->
                val group = list.filter { it.kind == kind }
                if (group.isEmpty()) return@forEach
                item { Section(stringResource(R.string.health_group, stringResource(title), group.size)) }
                item {
                    when (kind) {
                        HealthKind.NO_COUNTRY_CODE, HealthKind.TITLE_IS_COMPANY -> Button(
                            onClick = { scope.launch { val n = scanner.fix(group); vm.toast(context.resources.getQuantityString(R.plurals.health_fixed, n, n)); round++ } },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) { Text(stringResource(R.string.health_fix_all, group.size)) }
                        HealthKind.SHARED_NUMBER -> TextButton({ open(Routes.DUPLICATES) }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.health_review_duplicates)) }
                        HealthKind.STALE -> TextButton({
                            val phoneLabel = context.getString(R.string.health_phone)
                            // Never with one tap: list who and where first (F18).
                            scope.launch {
                                confirmStale = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    group.map { i ->
                                        val where = vm.c.contacts.details(i.contactId)?.rawContacts.orEmpty().map { it.account.displayLabel }.distinct()
                                        Triple(i, i.name, where.joinToString(", ").ifEmpty { phoneLabel })
                                    }
                                }
                            }
                        }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.health_auto_delete)) }
                        HealthKind.EMPTY -> TextButton({ vm.deleteContacts(group.map { it.contactId }); round++ }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.health_delete_all, group.size)) }
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
