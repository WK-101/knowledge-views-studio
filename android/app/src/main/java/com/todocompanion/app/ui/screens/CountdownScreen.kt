package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.CountdownEntity
import com.todocompanion.app.domain.LifeEvent
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.DateTimePickerDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private val CD_COLORS = listOf(0xFF6C4FE0, 0xFFE5484D, 0xFFF59E0B, 0xFF12A594, 0xFF3E7BFA, 0xFFEC4899)

/**
 * R43 — "Occasions": the countdown hub, grown into a life-events board (inspired by Birday, but a
 * unified app can go further). Birthdays show age & zodiac, anniversaries their Nth year, memorials
 * stay quiet — and any occasion can auto-spawn a "prepare" task. Grouped Today / This week / This
 * month / Later / Past. Offline; part of the backup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountdownScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val items by vm.countdowns.collectAsState()
    var editing by remember { mutableStateOf<CountdownEntity?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    Scaffold(topBar = {
        TopAppBar(expandedHeight = 52.dp,
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text("Occasions") },
            actions = { IconButton(onClick = { addOpen = true }) { Icon(Icons.Filled.Add, "Add occasion") } },
        )
    }) { padding ->
        if (items.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎂", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.size(10.dp))
                Text("Birthdays, anniversaries & countdowns", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Track the people and dates that matter — with age, the next occurrence, and an optional gift reminder.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                TextButton(onClick = { addOpen = true }) { Text("＋ New occasion") }
            }
        } else {
            val sorted = items.sortedBy { LifeEvent.sortKey(it, today) }
            val grouped = sorted.groupBy { LifeEvent.bucket(it, today) }
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OverviewStrip(sorted, today) }
                LifeEvent.Bucket.entries.forEach { bucket ->
                    val inBucket = grouped[bucket].orEmpty()
                    if (inBucket.isNotEmpty()) {
                        item(key = "hdr-${bucket.name}") {
                            Text(bucket.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                        }
                        items(inBucket, key = { it.id }) { c -> OccasionCard(c, today, onOpen = { editing = c }, onPin = { vm.toggleCountdownPin(c) }) }
                    }
                }
            }
        }
    }

    if (addOpen) OccasionDialog(null, onDismiss = { addOpen = false }, onDelete = {},
        onSave = { t, person, millis, type, yearly, yearKnown, emoji, color, notes, lead ->
            vm.saveOccasion(null, t, person, millis, type, yearly, yearKnown, emoji, color, notes, lead); addOpen = false
        })
    editing?.let { c ->
        OccasionDialog(c, onDismiss = { editing = null }, onDelete = { vm.deleteCountdown(c.id); editing = null },
            onSave = { t, person, millis, type, yearly, yearKnown, emoji, color, notes, lead ->
                vm.saveOccasion(c.id, t, person, millis, type, yearly, yearKnown, emoji, color, notes, lead); editing = null
            })
    }
}

@Composable
private fun OverviewStrip(sorted: List<CountdownEntity>, today: LocalDate) {
    val next = sorted.firstOrNull { LifeEvent.daysUntil(it, today) >= 0 } ?: return
    val nextDays = LifeEvent.daysUntil(next, today)
    val within30 = sorted.count { val d = LifeEvent.daysUntil(it, today); d in 0..30 }
    val birthdays = sorted.count { LifeEvent.type(it) == LifeEvent.EventType.BIRTHDAY }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(next.emoji ?: LifeEvent.type(next).emoji, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        nextDays == 0L -> "${next.personName.ifBlank { next.title }} is ${if (LifeEvent.type(next) == LifeEvent.EventType.BIRTHDAY) "celebrating today 🎉" else "today"}"
                        else -> "Next up: ${next.personName.ifBlank { next.title }} ${LifeEvent.daysLabel(nextDays)}"
                    },
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text("$within30 in the next 30 days · $birthdays ${if (birthdays == 1) "birthday" else "birthdays"} tracked",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OccasionCard(c: CountdownEntity, today: LocalDate, onOpen: () -> Unit, onPin: () -> Unit) {
    val type = LifeEvent.type(c)
    val next = LifeEvent.nextOccurrence(c, today)
    val days = LifeEvent.daysUntil(c, today)
    val accent = c.colorArgb?.let { Color(it) } ?: if (type.celebratory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val bg = if (type.celebratory) accent.copy(alpha = .12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)
    Surface(shape = RoundedCornerShape(16.dp), color = bg, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.emoji ?: type.emoji, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.personName.ifBlank { c.title }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LifeEvent.milestone(c, today)?.let { m ->
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(6.dp), color = accent.copy(alpha = .22f)) {
                            Text(m, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                }
                if (c.personName.isNotBlank() && c.title != c.personName) Text(c.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${next.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${next.dayOfMonth} ${next.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${next.year}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Age / years + zodiac chips.
                val chips = listOfNotNull(LifeEvent.ageChip(c, today), LifeEvent.zodiac(c))
                if (chips.isNotEmpty()) {
                    Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.forEach { chip ->
                            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .7f)) {
                                Text(chip, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                            }
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 6.dp)) {
                Text(if (days == 0L) "🎉" else "${kotlin.math.abs(days)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = accent)
                Text(when { days == 0L -> "today"; days > 0 -> if (days == 1L) "day left" else "days left"; else -> "days ago" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onPin) {
                Icon(if (c.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, "Pin to widget", tint = if (c.pinned) accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun OccasionDialog(
    existing: CountdownEntity?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (title: String, person: String, millis: Long, type: String, yearly: Boolean, yearKnown: Boolean, emoji: String?, color: Long, notes: String, leadDays: Int) -> Unit,
) {
    var type by remember { mutableStateOf(LifeEvent.EventType.from(existing?.eventType ?: "BIRTHDAY")) }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var person by remember { mutableStateOf(existing?.personName ?: "") }
    var emoji by remember { mutableStateOf(existing?.emoji ?: "") }
    var emojiOpen by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(existing?.colorArgb ?: CD_COLORS.first()) }
    var yearly by remember { mutableStateOf(existing?.yearly ?: true) }
    var yearKnown by remember { mutableStateOf(existing?.yearKnown ?: true) }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var leadDays by remember { mutableStateOf(existing?.prepLeadDays ?: 0) }
    var millis by remember { mutableStateOf(existing?.targetMillis ?: LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) }
    var showDate by remember { mutableStateOf(false) }
    val d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    // Applying a type also nudges the sensible yearly/emoji defaults (a birthday recurs; a countdown doesn't).
    fun applyType(t: LifeEvent.EventType) {
        type = t
        yearly = t.yearlyByDefault
        if (emoji.isBlank()) Unit // keep user's emoji; the card falls back to the type glyph anyway
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(title.trim(), person.trim(), millis, type.name, yearly, yearKnown, emoji.trim().ifBlank { null }, color, notes.trim(), leadDays) }) { Text("Save") } },
        dismissButton = { if (existing != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } else TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (existing == null) "New occasion" else "Occasion") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Type chips.
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LifeEvent.EventType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { applyType(t) },
                            label = { Text("${t.emoji} ${t.label}") },
                            colors = FilterChipDefaults.filterChipColors())
                    }
                }
                Spacer(Modifier.size(10.dp))
                // Emoji + name.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.width(64.dp).height(56.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f))
                            .clickable { emojiOpen = !emojiOpen },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (emoji.isBlank()) Text(type.emoji, style = MaterialTheme.typography.headlineSmall)
                        else Text(emoji, style = MaterialTheme.typography.headlineSmall)
                    }
                    Column(Modifier.weight(1f)) {
                        com.todocompanion.app.ui.components.AppTextField(person, { person = it }, singleLine = true, label = { Text(if (type.countsAge) "Whose (name)" else "Name") }, modifier = Modifier.fillMaxWidth())
                        com.todocompanion.app.ui.components.AppTextField(title, { title = it }, singleLine = true, label = { Text("Title (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
                if (emojiOpen) {
                    Spacer(Modifier.size(8.dp))
                    com.todocompanion.app.ui.components.EmojiGridPicker(current = emoji.ifBlank { null }, onPick = { emoji = it ?: ""; emojiOpen = false })
                }
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = { showDate = true }) { Text("${if (type == LifeEvent.EventType.BIRTHDAY) "Date of birth" else "Date"}: ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${d.year}") }
                // Yearly toggle.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Repeats every year", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = yearly, onCheckedChange = { yearly = it })
                }
                // Year-known toggle (only where age/years is meaningful).
                if (type.countsAge) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (type == LifeEvent.EventType.BIRTHDAY) "Show age" else "Count the years", style = MaterialTheme.typography.bodyMedium)
                            Text("Off if you only know the day, not the year", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = yearKnown, onCheckedChange = { yearKnown = it })
                    }
                }
                Spacer(Modifier.size(6.dp))
                // Colour row.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CD_COLORS.forEach { cc ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(cc)).clickable { color = cc }, contentAlignment = Alignment.Center) {
                            if (cc == color) Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
                        }
                    }
                }
                Spacer(Modifier.size(10.dp))
                com.todocompanion.app.ui.components.AppTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(10.dp))
                // Prepare-task lead time — the unified-app extra.
                Text("Remind me to prepare", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Auto-add a task before the day (a gift, a card, a plan).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(4.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Off", 1 to "1 day", 3 to "3 days", 7 to "1 week", 14 to "2 weeks", 30 to "1 month").forEach { (n, lbl) ->
                        val sel = leadDays == n
                        AssistChip(onClick = { leadDays = n }, label = { Text(lbl) },
                            colors = if (sel) AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .18f), labelColor = MaterialTheme.colorScheme.primary) else AssistChipDefaults.assistChipColors())
                    }
                }
            }
        },
    )
    if (showDate) DateTimePickerDialog(millis, { showDate = false }) { m -> millis = m; showDate = false }
}
