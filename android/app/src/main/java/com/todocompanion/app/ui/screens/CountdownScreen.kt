package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.CountdownEntity
import com.todocompanion.app.domain.LifeEvent
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.DateOnlyPickerDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private val CD_COLORS = listOf(0xFF6C4FE0, 0xFFE5484D, 0xFFF59E0B, 0xFF12A594, 0xFF3E7BFA, 0xFFEC4899)
private val UNITS = listOf("days" to "Days", "weeks" to "Weeks", "workdays" to "Work days", "hours" to "Hours", "sleeps" to "Sleeps")

/** Decode a base64 JPEG face to an ImageBitmap (cached by the string). */
@Composable
private fun rememberFace(b64: String?) = remember(b64) {
    b64?.let { runCatching {
        val bytes = android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull() }
}

/**
 * R45 — "Occasions", the life-events hub taken well beyond countdowns. Birthdays with age/zodiac/facts,
 * anniversaries with Nth-year milestones, count-up "time since", per-occasion units, categories,
 * favourites, archive, photo faces, a milestone radar and an "on this day" memory strip from your own
 * store. Consistent with the rest of the app: a FAB to add, a bottom-sheet editor. Offline; in the backup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountdownScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val items by vm.countdowns.collectAsState()
    var editing by remember { mutableStateOf<CountdownEntity?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    Scaffold(
        topBar = {
            TopAppBar(expandedHeight = 52.dp,
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Occasions") },
                actions = {
                    if (items.any { it.archived }) IconButton(onClick = { showArchived = !showArchived }) {
                        Icon(if (showArchived) Icons.Filled.Unarchive else Icons.Filled.Archive, "Archived", tint = if (showArchived) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { addOpen = true }) { Icon(Icons.Filled.Add, "New occasion") } },
    ) { padding ->
        if (items.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎂", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.size(10.dp))
                Text("Birthdays, anniversaries & countdowns", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Track the people and dates that matter — with age, the next occurrence, milestones and an optional gift reminder.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                TextButton(onClick = { addOpen = true }) { Text("＋ New occasion") }
            }
        } else {
            val visible = items.filter { showArchived == it.archived }
            val sorted = visible.sortedWith(compareByDescending<CountdownEntity> { it.favorite }.thenBy { LifeEvent.sortKey(it, today) })
            val radar = remember(items, today) { LifeEvent.radar(items, today) }
            val onThisDay = remember(items) { vm.onThisDay() }
            val weeksSubject = remember(items) { LifeEvent.lifeInWeeksSubject(items) }
            val historyFact = remember(today) { com.todocompanion.app.domain.Almanac.onThisDay(today) }
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 12.dp, 12.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OverviewStrip(sorted.filter { !it.archived }, today) }
                if (!showArchived && radar.isNotEmpty()) item { RadarCard(radar) }
                if (!showArchived && onThisDay.isNotEmpty()) item { OnThisDayCard(onThisDay) }
                if (!showArchived && weeksSubject != null) item { LifeInWeeksCard(weeksSubject, today) }
                if (!showArchived && historyFact != null) item { TodayInHistoryCard(historyFact) }
                val fav = sorted.filter { it.favorite }
                if (fav.isNotEmpty() && !showArchived) {
                    item(key = "hdr-fav") { GroupHeader("★ Favourites") }
                    items(fav, key = { it.id }) { c -> OccasionCard(c, today, onOpen = { editing = c }, onFav = { vm.toggleOccasionFavorite(c) }) }
                }
                val rest = if (showArchived) sorted else sorted.filter { !it.favorite }
                LifeEvent.Bucket.entries.forEach { bucket ->
                    val inB = rest.filter { LifeEvent.bucket(it, today) == bucket }
                    if (inB.isNotEmpty()) {
                        item(key = "hdr-${bucket.name}") { GroupHeader(bucket.label) }
                        items(inB, key = { it.id }) { c -> OccasionCard(c, today, onOpen = { editing = c }, onFav = { vm.toggleOccasionFavorite(c) }) }
                    }
                }
            }
        }
    }

    if (addOpen) OccasionEditorSheet(vm, null, onDismiss = { addOpen = false })
    editing?.let { c -> OccasionEditorSheet(vm, c, onDismiss = { editing = null }) }
}

@Composable
private fun GroupHeader(label: String) = Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 6.dp))

@Composable
private fun OverviewStrip(sorted: List<CountdownEntity>, today: LocalDate) {
    val next = sorted.firstOrNull { !it.countUp && LifeEvent.daysUntil(it, today) >= 0 } ?: return
    val nextDays = LifeEvent.daysUntil(next, today)
    val within30 = sorted.count { !it.countUp && LifeEvent.daysUntil(it, today) in 0..30 }
    val birthdays = sorted.count { LifeEvent.type(it) == LifeEvent.EventType.BIRTHDAY }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(next.emoji ?: LifeEvent.type(next).emoji, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (nextDays == 0L) "${next.personName.ifBlank { next.title }} is today 🎉" else "Next up: ${next.personName.ifBlank { next.title }} ${LifeEvent.daysLabel(nextDays)}",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$within30 in the next 30 days · $birthdays ${if (birthdays == 1) "birthday" else "birthdays"} tracked",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RadarCard(hits: List<LifeEvent.RadarHit>) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .4f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("✵ Milestone radar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Rare milestones you'd never think to compute.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            hits.take(4).forEach { h ->
                Text("${h.emoji} ${h.label} — ${LifeEvent.daysLabel(h.daysUntil)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

@Composable
private fun OnThisDayCard(entries: List<Pair<Int, com.todocompanion.app.data.entity.TaskEntity>>) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .4f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("◔ On this day", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("From your own history — no cloud, no photos.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            entries.take(4).forEach { (years, t) ->
                Text("$years ${if (years == 1) "year" else "years"} ago you finished “${t.title}”", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

/** #24 Today-in-history — a bundled offline almanac fact for today, beside your own On-This-Day. */
@Composable
private fun TodayInHistoryCard(fact: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("📜 Today in history", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("A bundled almanac — offline, no server.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(fact, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** #18 Life in weeks + #13 life-spent — a "4000 weeks" grid: one dot per week of an 80-year life,
 *  filled up to the weeks already lived. Rendered for a favourite/earliest birthday with a known year. */
@Composable
private fun LifeInWeeksCard(subject: CountdownEntity, today: LocalDate) {
    val lifeYears = 80
    val lived = LifeEvent.weeksLived(subject, today)
    val total = LifeEvent.totalLifeWeeks(lifeYears)
    val pct = LifeEvent.lifeSpentPct(subject, lifeYears, today) ?: 0
    val who = subject.personName.ifBlank { subject.title }
    val filled = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("▦ $who's life in weeks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Each dot is one week of an ${lifeYears}-year life — ${"%,d".format(lived)} lived, $pct%% spent.".replace("%%", "%"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val cols = 52
            val rows = lifeYears
            androidx.compose.foundation.Canvas(
                Modifier.fillMaxWidth().height((rows * 3.2f).dp)
            ) {
                val gap = size.width / cols
                val cell = gap * 0.62f
                val r = cell / 2f
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        val idx = row * cols + col
                        val cx = col * gap + gap / 2f
                        val cy = row * (size.height / rows) + (size.height / rows) / 2f
                        drawCircle(color = if (idx < lived) filled else empty, radius = r, center = androidx.compose.ui.geometry.Offset(cx, cy))
                    }
                }
            }
        }
    }
}

@Composable
private fun OccasionCard(c: CountdownEntity, today: LocalDate, onOpen: () -> Unit, onFav: () -> Unit) {
    val type = LifeEvent.type(c)
    val next = LifeEvent.nextOccurrence(c, today)
    val (count, unitLabel) = LifeEvent.displayCount(c, today)
    val accent = c.colorArgb?.let { Color(it) } ?: if (type.celebratory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val bg = if (type.celebratory) accent.copy(alpha = .12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)
    val face = rememberFace(c.photoBase64)
    val masked = c.locked
    Surface(shape = RoundedCornerShape(16.dp), color = bg, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (face != null && !masked) Image(face, null, Modifier.size(46.dp).clip(CircleShape).padding(end = 0.dp), contentScale = ContentScale.Crop)
            else Text(if (masked) "🔒" else (c.emoji ?: type.emoji), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (masked) "Private occasion" else c.personName.ifBlank { c.title }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!masked) LifeEvent.milestone(c, today)?.let { m ->
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(6.dp), color = accent.copy(alpha = .22f)) { Text(m, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)) }
                    }
                    if (c.locked) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp)) }
                }
                if (!masked) {
                    Text("${if (c.countUp) "since " else ""}${next.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${next.dayOfMonth} ${next.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${next.year}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val chips = listOfNotNull(LifeEvent.ageChip(c, today), LifeEvent.zodiac(c), c.category.takeIf { it.isNotBlank() })
                    if (chips.isNotEmpty()) Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.forEach { chip -> Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .7f)) { Text(chip, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)) } }
                    }
                    // #14/#17 keep-in-touch: how long since you last logged a moment, flagged when it's lapsed.
                    com.todocompanion.app.domain.Moments.cadenceLine(c, today)?.let { line ->
                        val overdue = com.todocompanion.app.domain.Moments.cadenceOverdue(c, today)
                        Text(line, style = MaterialTheme.typography.labelSmall, fontWeight = if (overdue) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 6.dp)) {
                Text(if (!c.countUp && count == 0L) "🎉" else "$count", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = accent)
                Text(if (!c.countUp && count == 0L) "today" else if (c.countUp) "$unitLabel ago" else "$unitLabel left", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onFav) { Icon(if (c.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, "Favourite", tint = if (c.favorite) accent else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OccasionEditorSheet(vm: AppViewModel, existing: CountdownEntity?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var type by remember { mutableStateOf(LifeEvent.EventType.from(existing?.eventType ?: "BIRTHDAY")) }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var person by remember { mutableStateOf(existing?.personName ?: "") }
    var emoji by remember { mutableStateOf(existing?.emoji ?: "") }
    var emojiOpen by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(existing?.colorArgb ?: CD_COLORS.first()) }
    var yearly by remember { mutableStateOf(existing?.yearly ?: true) }
    var yearKnown by remember { mutableStateOf(existing?.yearKnown ?: true) }
    var countUp by remember { mutableStateOf(existing?.countUp ?: false) }
    var unit by remember { mutableStateOf(existing?.unit ?: "days") }
    var category by remember { mutableStateOf(existing?.category ?: "") }
    var favorite by remember { mutableStateOf(existing?.favorite ?: false) }
    var locked by remember { mutableStateOf(existing?.locked ?: false) }
    var photoB64 by remember { mutableStateOf(existing?.photoBase64) }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var leadDays by remember { mutableStateOf(existing?.prepLeadDays ?: 0) }
    var keepInTouch by remember { mutableStateOf(existing?.keepInTouchDays ?: 0) }
    var recurCal by remember { mutableStateOf(existing?.recurCalendar ?: "gregorian") }
    // Moments (relationship loop / know-them). Persisted immediately on the row; mirrored here so the list
    // in the sheet updates optimistically without re-observing.
    var momentsLocal by remember { mutableStateOf(existing?.let { com.todocompanion.app.domain.Moments.parse(it) } ?: emptyList()) }
    var momentDraft by remember { mutableStateOf("") }
    var millis by remember { mutableStateOf(existing?.targetMillis ?: LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) }
    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    fun build(): CountdownEntity = (existing ?: CountdownEntity(id = java.util.UUID.randomUUID().toString(), title = title, targetMillis = millis, createdAt = System.currentTimeMillis()))
        .copy(title = title, personName = person, targetMillis = millis, eventType = type.name, yearly = yearly, yearKnown = yearKnown,
            emoji = emoji.trim().ifBlank { null }, colorArgb = color, notes = notes, prepLeadDays = leadDays,
            countUp = countUp, unit = unit, category = category.trim(), favorite = favorite, locked = locked, photoBase64 = photoB64,
            keepInTouchDays = keepInTouch, recurCalendar = recurCal, momentsJson = com.todocompanion.app.domain.Moments.encode(momentsLocal))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text(if (existing == null) "New occasion" else "Occasion", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LifeEvent.EventType.entries.forEach { t ->
                    FilterChip(selected = type == t, onClick = { type = t; yearly = t.yearlyByDefault }, label = { Text("${t.emoji} ${t.label}") })
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val face = rememberFace(photoB64)
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f)).clickable { emojiOpen = !emojiOpen }, contentAlignment = Alignment.Center) {
                    if (face != null) Image(face, null, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                    else Text(emoji.ifBlank { type.emoji }, style = MaterialTheme.typography.headlineSmall)
                }
                Column(Modifier.weight(1f)) {
                    com.todocompanion.app.ui.components.AppTextField(person, { person = it }, singleLine = true, label = { Text(if (type.countsAge) "Whose (name)" else "Name") }, modifier = Modifier.fillMaxWidth())
                    com.todocompanion.app.ui.components.AppTextField(title, { title = it }, singleLine = true, label = { Text("Title (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            if (emojiOpen) { Spacer(Modifier.height(8.dp)); com.todocompanion.app.ui.components.EmojiGridPicker(current = emoji.ifBlank { null }, onPick = { emoji = it ?: ""; emojiOpen = false }) }
            // Photo face row
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { com.todocompanion.app.util.SystemPicker.galleryOne(onError = { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() }) { uri -> vm.imageUriToBase64(uri) { b64 -> if (b64 != null) photoB64 = b64 } } }) { Text(if (photoB64 == null) "Add photo" else "Change photo") }
                if (photoB64 != null) TextButton(onClick = { photoB64 = null }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { showDate = true }) { Text("${if (type == LifeEvent.EventType.BIRTHDAY) "Date of birth" else "Date"}: ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${d.year}") }
            // Toggles
            EditorSwitch("Repeats every year", yearly) { yearly = it }
            EditorSwitch("Count up (time since)", countUp) { countUp = it }
            if (type.countsAge) EditorSwitch(if (type == LifeEvent.EventType.BIRTHDAY) "Show age" else "Count the years", yearKnown) { yearKnown = it }
            EditorSwitch("Favourite (pin to top)", favorite) { favorite = it }
            EditorSwitch("Private (mask in the list)", locked) { locked = it }
            // Unit
            Spacer(Modifier.height(6.dp))
            Text("Show in", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UNITS.forEach { (key, label) -> FilterChip(selected = unit == key, onClick = { unit = key }, label = { Text(label) }) }
            }
            Spacer(Modifier.height(8.dp))
            com.todocompanion.app.ui.components.AppTextField(category, { category = it }, singleLine = true, label = { Text("Category (optional)") }, modifier = Modifier.fillMaxWidth())
            // #3 alternate-calendar recurrence — only meaningful when the occasion repeats yearly.
            if (yearly) {
                Spacer(Modifier.height(8.dp))
                Text("Repeats on", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = recurCal == "gregorian", onClick = { recurCal = "gregorian" }, label = { Text("Gregorian") })
                    FilterChip(selected = recurCal == "hijri", onClick = { recurCal = "hijri" }, label = { Text("Islamic (Hijri)") })
                }
                if (recurCal == "hijri") com.todocompanion.app.domain.HijriRecur.originLabel(d)?.let {
                    Text("On $it each Hijri year (drifts ~11 days earlier each Gregorian year).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // #14/#17 keep-in-touch cadence.
            Spacer(Modifier.height(8.dp))
            Text("Keep in touch", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Off", 7 to "Weekly", 14 to "Fortnightly", 30 to "Monthly", 90 to "Quarterly").forEach { (n, lbl) ->
                    FilterChip(selected = keepInTouch == n, onClick = { keepInTouch = n }, label = { Text(lbl) })
                }
            }
            Spacer(Modifier.height(8.dp))
            // Colour
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CD_COLORS.forEach { cc -> Box(Modifier.size(28.dp).clip(CircleShape).background(Color(cc)).clickable { color = cc }, contentAlignment = Alignment.Center) { if (cc == color) Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White)) } }
            }
            Spacer(Modifier.height(10.dp))
            com.todocompanion.app.ui.components.AppTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Text("Remind me to prepare", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("Auto-add a task before the day (a gift, a card, a plan).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Off", 1 to "1 day", 3 to "3 days", 7 to "1 week", 14 to "2 weeks", 30 to "1 month").forEach { (n, lbl) ->
                    val sel = leadDays == n
                    AssistChip(onClick = { leadDays = n }, label = { Text(lbl) }, colors = if (sel) AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .18f), labelColor = MaterialTheme.colorScheme.primary) else AssistChipDefaults.assistChipColors())
                }
            }
            // #14/#20 Moments & connection — log a moment (or answer a know-them prompt). Saved occasions only.
            if (existing != null) {
                Spacer(Modifier.height(12.dp))
                Text("Moments & connection", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val prompt = remember(existing.id) { com.todocompanion.app.domain.KnowThem.questionFor(existing) }
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .35f), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Get to know them", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(prompt, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { if (momentDraft.isBlank()) momentDraft = "$prompt — " }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Answer this") }
                    }
                }
                com.todocompanion.app.ui.components.AppTextField(momentDraft, { momentDraft = it }, label = { Text("Log a moment or a note") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = momentDraft.isNotBlank(), onClick = {
                        vm.logOccasionMoment(existing, momentDraft)
                        momentsLocal = (momentsLocal + com.todocompanion.app.domain.Moment(LocalDate.now().toEpochDay(), momentDraft.trim())).sortedByDescending { it.d }
                        momentDraft = ""
                    }) { Text("Log moment") }
                }
                momentsLocal.take(6).forEach { m ->
                    val dd = LocalDate.ofEpochDay(m.d)
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.n, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("${dd.dayOfMonth} ${dd.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${dd.year}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.removeOccasionMoment(existing, m); momentsLocal = momentsLocal.filterNot { it.d == m.d && it.n == m.n } }) {
                            Icon(Icons.Filled.Close, "Remove moment", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            // Date-fact pack (saved birthdays)
            if (existing != null && type == LifeEvent.EventType.BIRTHDAY && yearKnown) {
                Spacer(Modifier.height(12.dp))
                Text("Facts", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val ex = build()
                listOfNotNull(
                    LifeEvent.chineseZodiac(ex)?.let { "Chinese zodiac: $it" },
                    LifeEvent.lifePath(ex)?.let { "Life-path number: $it" },
                    LifeEvent.dayOfWeekBorn(ex)?.let { "Born on a $it" },
                    LifeEvent.goldenBirthday(ex)?.let { (age, dt) -> "Golden birthday: turns $age on ${dt.year}" },
                    LifeEvent.nextRoundDayMilestone(ex)?.let { (n, dt) -> "${"%,d".format(n)} days old on ${dt.dayOfMonth} ${dt.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${dt.year}" },
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 1.dp)) }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (existing != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { shareOccasion(ctx, build()) }) { Text("Share") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { vm.saveOccasionRow(build()); onDismiss() }) { Text("Save", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
    if (showDate) DateOnlyPickerDialog(millis, { showDate = false }) { m -> millis = m; showDate = false }
    if (confirmDelete) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDelete = false },
        confirmButton = { TextButton(onClick = { existing?.let { vm.deleteCountdown(it.id) }; confirmDelete = false; onDismiss() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        title = { Text("Delete this occasion?") },
    )
}

@Composable
private fun EditorSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Share-as-card: an emoji text summary via the OS share sheet (ACTION_SEND, permission-free). */
private fun shareOccasion(ctx: android.content.Context, c: CountdownEntity) {
    val today = LocalDate.now()
    val next = LifeEvent.nextOccurrence(c, today)
    val who = c.personName.ifBlank { c.title }
    val days = LifeEvent.primaryDays(c, today)
    val line = buildString {
        append("${c.emoji ?: LifeEvent.type(c).emoji} $who\n")
        append("${next.dayOfMonth} ${next.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${next.year}\n")
        append(if (c.countUp) "$days days and counting" else if (days == 0L) "It's today! 🎉" else LifeEvent.daysLabel(days))
        LifeEvent.ageChip(c, today)?.let { append(" · $it") }
    }
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, line) }
    runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Share")) }
}
