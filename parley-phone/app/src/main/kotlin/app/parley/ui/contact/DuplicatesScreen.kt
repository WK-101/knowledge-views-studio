package app.parley.ui.contact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.Duplicates
import app.parley.ui.Avatar
import app.parley.ui.Bidi
import app.parley.ui.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(vm: AppViewModel, back: () -> Unit) {
    val all by vm.contacts.collectAsStateWithLifecycle()
    val dismissed = remember { mutableStateListOf<Long>() }
    val scope = rememberCoroutineScope()
    val groups by produceState<List<List<ContactSummary>>?>(null, all) {
        value = withContext(Dispatchers.Default) { Duplicates.find(all.orEmpty()) }
    }
    // U7: scroll-linked top-bar tint.
    val barTint = androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior()
    // C2: "Back up first?" before the first merge (asked once per visit).
    val backupFirst = app.parley.ui.backup.rememberBackupFirst(vm)
    Scaffold(modifier = Modifier.nestedScroll(barTint.nestedScrollConnection), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.dup_title)) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.main_back)) } }, scrollBehavior = barTint)
    }) { p ->
        val res = LocalResources.current
        val list = groups?.filter { g -> g.first().id !in dismissed }
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(Icons.Rounded.DoneAll, stringResource(R.string.dup_none), stringResource(R.string.dup_none_body), Modifier.padding(p))
            else -> LazyColumn(Modifier.padding(p), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(
                        stringResource(R.string.dup_explainer),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(list, key = { it.first().id }) { g ->
                    Card {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            g.forEach { c ->
                                ListItem(
                                    leadingContent = { Avatar(c.displayName, c.photoUri, 40.dp) },
                                    headlineContent = { Text(c.displayName) },
                                    supportingContent = { Text((c.phones.map { Bidi.ltr(it.number) } + c.emails).take(2).joinToString(stringResource(R.string.main_separator))) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                                TextButton({ dismissed += g.first().id }) { Text(stringResource(R.string.dup_not)) }
                                Button({
                                    backupFirst.ask(g.size, 1) {
                                        scope.launch {
                                            vm.c.contacts.join(g.map { it.id })
                                            dismissed += g.first().id
                                            vm.toast(res.getQuantityString(R.plurals.sel_merged, g.size, g.size))
                                        }
                                    }
                                }) { Text(stringResource(R.string.dup_merge)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
