@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.rules

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.data.db.CollectionWithCount
import com.cairn.reader.domain.rules.Rule
import com.cairn.reader.domain.rules.RuleAction
import com.cairn.reader.domain.rules.RuleActionType
import com.cairn.reader.domain.rules.RuleCondition
import com.cairn.reader.domain.rules.RuleField
import com.cairn.reader.domain.rules.RuleOp

@Composable
fun RulesScreen(
    padding: PaddingValues,
    onOpenDrawer: () -> Unit = {},
    viewModel: RulesViewModel = hiltViewModel(),
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Rule?>(null) }

    val current = editing
    if (current != null) {
        RuleEditor(
            initial = current,
            collections = collections,
            onSave = { viewModel.save(it); editing = null },
            onCancel = { editing = null },
        )
        return
    }

    val scheme = MaterialTheme.colorScheme
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (rules.isEmpty()) "Rules" else "Rules · ${rules.size}", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.open_navigation)) } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Rule.new() }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.new_rule))
            }
        },
    ) { inner ->
        if (rules.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(inner).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(48.dp), tint = scheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.automate_your_reading), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.create_rules_that_run_on_every),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rules, key = { it.id }) { rule ->
                    RuleCard(
                        rule = rule,
                        collections = collections,
                        onToggle = { viewModel.setEnabled(rule.id, it) },
                        onEdit = { editing = rule },
                        onDelete = { viewModel.delete(rule.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleCard(
    rule: Rule,
    collections: List<CollectionWithCount>,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onEdit,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rule.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                summarize(rule, collections),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
}

private fun summarize(rule: Rule, collections: List<CollectionWithCount>): String {
    val join = if (rule.matchAll) " AND " else " OR "
    val conds = rule.conditions.joinToString(join) { c ->
        "${c.field.label.lowercase()} ${c.op.label} \"${c.value}\""
    }.ifBlank { "(no conditions)" }
    val acts = rule.actions.joinToString(", ") { a ->
        when (a.type) {
            RuleActionType.ADD_TAG -> "tag \"${a.value.orEmpty()}\""
            RuleActionType.ADD_TO_COLLECTION -> "→ ${collections.firstOrNull { it.id == a.value }?.name ?: "collection"}"
            else -> a.type.label.lowercase()
        }
    }.ifBlank { "(no actions)" }
    return "If $conds → $acts"
}

@Composable
private fun RuleEditor(
    initial: Rule,
    collections: List<CollectionWithCount>,
    onSave: (Rule) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var matchAll by remember { mutableStateOf(initial.matchAll) }
    var stopAfter by remember { mutableStateOf(initial.stopAfter) }
    val conditions = remember { androidx.compose.runtime.mutableStateListOf<RuleCondition>().apply { addAll(initial.conditions.ifEmpty { listOf(RuleCondition(RuleField.ANY, RuleOp.CONTAINS, "")) }) } }
    val actions = remember { androidx.compose.runtime.mutableStateListOf<RuleAction>().apply { addAll(initial.actions.ifEmpty { listOf(RuleAction(RuleActionType.MARK_READ)) }) } }
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_rule), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel)) } },
                actions = {
                    TextButton(onClick = {
                        onSave(
                            initial.copy(
                                name = name.trim().ifBlank { "Untitled rule" },
                                matchAll = matchAll,
                                stopAfter = stopAfter,
                                conditions = conditions.toList(),
                                actions = actions.toList(),
                            )
                        )
                    }) { Text(stringResource(R.string.save)) }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.rule_name)) }, modifier = Modifier.fillMaxWidth(),
            )

            // Match mode.
            Column {
                Text(stringResource(R.string.when_an_article_matches), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = matchAll, onClick = { matchAll = true }, label = { Text(stringResource(R.string.all_conditions)) })
                    FilterChip(selected = !matchAll, onClick = { matchAll = false }, label = { Text(stringResource(R.string.any_condition)) })
                }
            }

            // Conditions.
            conditions.forEachIndexed { i, cond ->
                ConditionRow(
                    condition = cond,
                    onChange = { conditions[i] = it },
                    onRemove = { if (conditions.size > 1) conditions.removeAt(i) },
                )
            }
            OutlinedButton(onClick = { conditions.add(RuleCondition(RuleField.ANY, RuleOp.CONTAINS, "")) }) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.add_condition))
            }

            // Actions.
            Text(stringResource(R.string.then), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            actions.forEachIndexed { i, act ->
                ActionRow(
                    action = act,
                    collections = collections,
                    onChange = { actions[i] = it },
                    onRemove = { if (actions.size > 1) actions.removeAt(i) },
                )
            }
            OutlinedButton(onClick = { actions.add(RuleAction(RuleActionType.MARK_READ)) }) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.add_action))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.stop_after_this_rule), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.don_t_evaluate_later_rules_once), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Switch(checked = stopAfter, onCheckedChange = { stopAfter = it })
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ConditionRow(
    condition: RuleCondition,
    onChange: (RuleCondition) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EnumDropdown(
                label = condition.field.label,
                options = RuleField.entries.map { it.label },
                modifier = Modifier.weight(1f),
                onSelect = { idx -> onChange(condition.copy(field = RuleField.entries[idx])) },
            )
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.remove_condition)) }
        }
        Spacer(Modifier.height(6.dp))
        EnumDropdown(
            label = condition.op.label,
            options = RuleOp.entries.map { it.label },
            modifier = Modifier.fillMaxWidth(),
            onSelect = { idx -> onChange(condition.copy(op = RuleOp.entries[idx])) },
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = condition.value, onValueChange = { onChange(condition.copy(value = it)) },
            singleLine = true, label = { Text(stringResource(R.string.value)) }, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ActionRow(
    action: RuleAction,
    collections: List<CollectionWithCount>,
    onChange: (RuleAction) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EnumDropdown(
                label = action.type.label,
                options = RuleActionType.entries.map { it.label },
                modifier = Modifier.weight(1f),
                onSelect = { idx ->
                    val type = RuleActionType.entries[idx]
                    onChange(RuleAction(type, if (type.needsValue) action.value else null))
                },
            )
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.remove_action)) }
        }
        when (action.type) {
            RuleActionType.ADD_TAG -> {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = action.value.orEmpty(), onValueChange = { onChange(action.copy(value = it)) },
                    singleLine = true, label = { Text(stringResource(R.string.tag_name)) }, modifier = Modifier.fillMaxWidth(),
                )
            }
            RuleActionType.ADD_TO_COLLECTION -> {
                Spacer(Modifier.height(6.dp))
                val currentName = collections.firstOrNull { it.id == action.value }?.name ?: "Choose collection…"
                if (collections.isEmpty()) {
                    Text(stringResource(R.string.create_a_collection_first), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    EnumDropdown(
                        label = currentName,
                        options = collections.map { it.name },
                        modifier = Modifier.fillMaxWidth(),
                        onSelect = { idx -> onChange(action.copy(value = collections[idx].id)) },
                    )
                }
            }
            else -> {}
        }
    }
}

/** A compact dropdown that shows [label] and offers [options], calling [onSelect] with the index. */
@Composable
private fun EnumDropdown(
    label: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, maxLines = 1, modifier = Modifier.weight(1f))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { idx, opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(idx); open = false })
            }
        }
    }
}
