package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
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
fun CountdownScreen(vm: AppViewModel, onBack: () -> Unit, initialOpenId: String? = null) {
    BackHandler(onBack = onBack)
    val items by vm.countdowns.collectAsState()
    var editing by remember { mutableStateOf<CountdownEntity?>(null) }
    var detailsFor by remember { mutableStateOf<CountdownEntity?>(null) }   // long-press → per-entry details
    var addOpen by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var showInsights by remember { mutableStateOf(false) }                   // reflective cards, folded by default
    var filter by remember { mutableStateOf(OccasionFilter()) }
    var filterOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var overflowOpen by remember { mutableStateOf(false) }
    val screenCtx = LocalContext.current
    val today = LocalDate.now()

    // R48/R51 — deep-link: open a specific occasion's DETAILS card once when arriving from the calendar
    // or a list (not the editor). Consume the id so dismissing it (or any later items change) doesn't
    // re-open it; the details sheet offers an Edit button for those who want to change the entry.
    var consumedOpenId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(initialOpenId, items) {
        if (initialOpenId != null && initialOpenId != consumedOpenId && detailsFor == null && editing == null) {
            items.firstOrNull { it.id == initialOpenId }?.let { detailsFor = it; consumedOpenId = initialOpenId }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(expandedHeight = 52.dp,
                navigationIcon = { IconButton(onClick = if (searchOpen) ({ searchOpen = false; query = "" }) else onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    if (searchOpen) androidx.compose.material3.TextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search occasions") },
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                    ) else Text("Occasions")
                },
                actions = {
                    IconButton(onClick = { if (searchOpen) { searchOpen = false; query = "" } else searchOpen = true }) {
                        Icon(if (searchOpen) Icons.Filled.Close else Icons.Filled.Search, "Search")
                    }
                    IconButton(onClick = { filterOpen = true }) {
                        Icon(Icons.Filled.FilterList, "Filter", tint = if (filter.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (items.any { it.archived }) IconButton(onClick = { showArchived = !showArchived }) {
                        Icon(if (showArchived) Icons.Filled.Unarchive else Icons.Filled.Archive, "Archived", tint = if (showArchived) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { overflowOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Import birthdays (.vcf)") },
                                leadingIcon = { Icon(Icons.Filled.Cake, null, Modifier.size(18.dp)) },
                                onClick = {
                                    overflowOpen = false
                                    com.todocompanion.app.util.SystemPicker.openFile(
                                        arrayOf("text/vcard", "text/x-vcard", "text/directory", "application/octet-stream", "*/*"),
                                        onError = { android.widget.Toast.makeText(screenCtx, it, android.widget.Toast.LENGTH_LONG).show() }
                                    ) { uri -> vm.importVcardBirthdays(uri) }
                                })
                        }
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
            val q = query.trim()
            val visible = items.filter {
                showArchived == it.archived && filter.matches(it, today) &&
                    (q.isBlank() || it.title.contains(q, true) || it.personName.contains(q, true) || it.category.contains(q, true))
            }
            val sorted = visible.sortedWith(compareByDescending<CountdownEntity> { it.favorite }.thenBy { LifeEvent.sortKey(it, today) })
            val radar = remember(items, today) { LifeEvent.radar(items, today) }
            val onThisDay = remember(items) { vm.onThisDay() }
            val historyFact = remember(today) { com.todocompanion.app.domain.Almanac.onThisDay(today) }
            // R47 frontier read-models (computed from data we already hold)
            val digest = remember(items) { vm.weekDigest() }
            val drift = remember(items) { vm.driftPeople() }
            val achievements = remember(items) { vm.achievementAnniversaries() }
            val wrapped = remember(items) { vm.yearInPeople() }
            val unlockable = remember(items) { items.filter { it.sealedLetter.isNotBlank() && it.sealedUntil in 1..System.currentTimeMillis() } }
            val nameById = remember(items) { items.associate { it.id to it.personName.ifBlank { it.title } } }
            val chapters = remember(items) { vm.lifeChapters() }
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 12.dp, 12.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OverviewStrip(sorted.filter { !it.archived }, today) }
                // Actionable cards stay up top; the reflective "insights" fold away so the list is occasion-first.
                if (!showArchived && (digest.occasions > 0 || digest.tasksDue > 0)) item { WeekDigestCard(digest) }
                if (!showArchived && drift.isNotEmpty()) item { DriftCard(drift, onOpen = { editing = it }) }
                if (!showArchived && unlockable.isNotEmpty()) item { SealedLettersReadyCard(unlockable, onOpen = { editing = it }) }
                val hasInsights = !showArchived && (radar.isNotEmpty() || onThisDay.isNotEmpty() || achievements.isNotEmpty() || wrapped.moments > 0 || chapters.isNotEmpty() || historyFact != null)
                if (hasInsights) {
                    item(key = "hdr-insights") {
                        Row(Modifier.fillMaxWidth().clickable { showInsights = !showInsights }.padding(start = 4.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (showInsights) "▾ Insights" else "▸ Insights", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (showInsights) {
                        if (radar.isNotEmpty()) item { RadarCard(radar) }
                        if (onThisDay.isNotEmpty()) item { OnThisDayCard(onThisDay) }
                        if (achievements.isNotEmpty()) item { AchievementsCard(achievements) }
                        if (wrapped.moments > 0) item { WrappedCard(wrapped) }
                        if (chapters.isNotEmpty()) item { ChaptersCard(chapters) }
                        if (historyFact != null) item { TodayInHistoryCard(historyFact) }
                    }
                }
                val fav = sorted.filter { it.favorite }
                if (fav.isNotEmpty() && !showArchived) {
                    item(key = "hdr-fav") { GroupHeader("★ Favourites") }
                    items(fav, key = { it.id }) { c -> OccasionCard(c, today, onOpen = { editing = c }, onFav = { vm.toggleOccasionFavorite(c) }, onLongPress = { detailsFor = c }, chainNextName = c.chainNextId?.let { nameById[it] }) }
                }
                val rest = if (showArchived) sorted else sorted.filter { !it.favorite }
                LifeEvent.Bucket.entries.forEach { bucket ->
                    val inB = rest.filter { LifeEvent.bucket(it, today) == bucket }
                    if (inB.isNotEmpty()) {
                        item(key = "hdr-${bucket.name}") { GroupHeader(bucket.label) }
                        items(inB, key = { it.id }) { c -> OccasionCard(c, today, onOpen = { editing = c }, onFav = { vm.toggleOccasionFavorite(c) }, onLongPress = { detailsFor = c }, chainNextName = c.chainNextId?.let { nameById[it] }) }
                    }
                }
                if (sorted.isEmpty()) item { Text("No occasions match this filter.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    if (addOpen) OccasionEditorSheet(vm, null, onDismiss = { addOpen = false })
    editing?.let { c -> OccasionEditorSheet(vm, c, onDismiss = { editing = null }) }
    detailsFor?.let { c -> OccasionDetailsSheet(c, today, onDismiss = { detailsFor = null }, onEdit = { detailsFor = null; editing = c }) }
    if (filterOpen) OccasionFilterSheet(filter, items, onApply = { filter = it }, onDismiss = { filterOpen = false })
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

/** #12 This-week digest — occasions + tasks due + habits, fused from the one local store. */
@Composable
private fun WeekDigestCard(d: com.todocompanion.app.domain.LifeReadModels.WeekDigest) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .4f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("🗓 This week in your life", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(buildString {
                append("${d.occasions} occasion${if (d.occasions == 1) "" else "s"} · ${d.tasksDue} task${if (d.tasksDue == 1) "" else "s"} due")
                if (d.habitsActive > 0) append(" · ${d.habitsActive} habit${if (d.habitsActive == 1) "" else "s"} running")
            }, style = MaterialTheme.typography.bodyMedium)
            d.nextLine?.let { Text("Next up: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** #25/#26 Drift radar — people whose keep-in-touch cadence has lapsed, most overdue first. */
@Composable
private fun DriftCard(people: List<CountdownEntity>, onOpen: (CountdownEntity) -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .35f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("👋 Reach out", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Cadences you set that have quietly slipped.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            people.take(4).forEach { c ->
                val who = c.personName.ifBlank { c.title }
                val since = com.todocompanion.app.domain.Moments.daysSinceLast(c)
                Text("• $who — ${if (since == null) "no moments yet" else "$since days"}", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(c) }.padding(vertical = 1.dp))
            }
        }
    }
}

/** #31 Sealed letters that have reached their unlock date. */
@Composable
private fun SealedLettersReadyCard(letters: List<CountdownEntity>, onOpen: (CountdownEntity) -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .45f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("✉️ A letter has unlocked", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            letters.take(3).forEach { c ->
                Text("Open ${c.personName.ifBlank { c.title }}'s sealed letter →", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().clickable { onOpen(c) }.padding(vertical = 2.dp))
            }
        }
    }
}

/** #29 Anniversaries of your wins — starred/high-priority tasks finished on this day in a past year. */
@Composable
private fun AchievementsCard(entries: List<Pair<Int, com.todocompanion.app.data.entity.TaskEntity>>) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .4f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("🏆 Anniversaries of your wins", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            entries.take(4).forEach { (years, t) ->
                Text("$years ${if (years == 1) "year" else "years"} ago you achieved “${t.title}”", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

/** #34 Year in people — a private, offline "wrapped". */
@Composable
private fun WrappedCard(w: com.todocompanion.app.domain.LifeReadModels.YearInPeople) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("✨ Your year in people", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("${w.moments} moment${if (w.moments == 1) "" else "s"} logged this year" +
                (w.topPerson?.let { " · most with $it (${w.topCount})" } ?: ""),
                style = MaterialTheme.typography.bodyMedium)
            Text("${w.birthdays} birthday${if (w.birthdays == 1) "" else "s"} tracked · ${w.milestones} milestone${if (w.milestones == 1) "" else "s"} this year",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** #30 Chapters of your life — years segmented by their relative fullness, from your own record. */
@Composable
private fun ChaptersCard(chapters: List<com.todocompanion.app.domain.LifeReadModels.Chapter>) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("📖 Chapters of your life", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Segmented from your own record — no averages.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            chapters.forEach { ch ->
                Text("${ch.year} — ${ch.label} (${ch.count} done)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

/** #18 Life in weeks + #13 life-spent — a "4000 weeks" grid: one dot per week of an 80-year life,
 *  filled up to the weeks already lived. Rendered for a favourite/earliest birthday with a known year. */
@Composable
private fun LifeInWeeksCard(subject: CountdownEntity, today: LocalDate, trackedHoursThisYear: Int) {
    val lifeYears = 80
    val lived = LifeEvent.weeksLived(subject, today)
    val total = LifeEvent.totalLifeWeeks(lifeYears)
    val pct = LifeEvent.lifeSpentPct(subject, lifeYears, today) ?: 0
    val who = subject.personName.ifBlank { subject.title }
    val filled = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)
    // #33 life-clock: how far through this calendar year we are.
    val yearPct = ((today.dayOfYear.toFloat() / (if (today.isLeapYear) 366 else 365)) * 100).toInt()
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("▦ $who's life in weeks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Each dot is one week of an ${lifeYears}-year life — ${"%,d".format(lived)} lived, $pct%% spent.".replace("%%", "%"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // #13 honest "life spent" from real tracked time · #33 the year's life-clock.
            Text("This year: $yearPct% elapsed" + (if (trackedHoursThisYear > 0) " · ${"%,d".format(trackedHoursThisYear)} h you actually tracked" else ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun OccasionCard(c: CountdownEntity, today: LocalDate, onOpen: () -> Unit, onFav: () -> Unit, onLongPress: () -> Unit = {}, chainNextName: String? = null) {
    val type = LifeEvent.type(c)
    val next = LifeEvent.nextOccurrence(c, today)
    val (count, unitLabel) = LifeEvent.displayCount(c, today)
    val accent = c.colorArgb?.let { Color(it) } ?: if (type.celebratory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val bg = if (type.celebratory) accent.copy(alpha = .12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)
    val face = rememberFace(c.photoBase64)
    val masked = c.locked
    Surface(shape = RoundedCornerShape(16.dp), color = bg, modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongPress)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (face != null && !masked) Image(face, null, Modifier.size(46.dp).clip(CircleShape).padding(end = 0.dp), contentScale = ContentScale.Crop)
            else Text(if (masked) "🔒" else (c.emoji ?: type.emoji), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (masked) "Private occasion" else c.personName.ifBlank { c.title }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
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
                    // #32 countdown chains — the true next step in a sequence.
                    chainNextName?.let { Text("→ then $it", style = MaterialTheme.typography.labelSmall, color = accent, modifier = Modifier.padding(top = 2.dp)) }
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
    var chainNext by remember { mutableStateOf(existing?.chainNextId) }
    var letter by remember { mutableStateOf(existing?.sealedLetter ?: "") }
    var sealedUntil by remember { mutableStateOf(existing?.sealedUntil ?: 0L) }
    var showSealDate by remember { mutableStateOf(false) }
    val allOccasions by vm.countdowns.collectAsState()
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
            keepInTouchDays = keepInTouch, recurCalendar = recurCal, momentsJson = com.todocompanion.app.domain.Moments.encode(momentsLocal),
            chainNextId = chainNext, sealedLetter = letter, sealedUntil = sealedUntil)

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
            // #32 countdown chains — link the occasion that comes next in a sequence.
            val chainOptions = allOccasions.filter { it.id != existing?.id && !it.archived }
            if (chainOptions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Then comes… (chain)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = chainNext == null, onClick = { chainNext = null }, label = { Text("None") })
                    chainOptions.take(12).forEach { o ->
                        FilterChip(selected = chainNext == o.id, onClick = { chainNext = o.id }, label = { Text(o.personName.ifBlank { o.title }.take(16)) })
                    }
                }
            }
            // #31 letter to the future — sealed until a date you choose.
            Spacer(Modifier.height(8.dp))
            val sealedLive = sealedUntil > System.currentTimeMillis()
            if (existing != null && letter.isNotBlank() && sealedLive) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("✉️ Sealed letter", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        val u = Instant.ofEpochMilli(sealedUntil).atZone(ZoneId.systemDefault()).toLocalDate()
                        Text("Locked until ${u.dayOfMonth} ${u.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${u.year}. It'll surface on the Occasions page that day.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { letter = ""; sealedUntil = 0L }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }
            } else {
                com.todocompanion.app.ui.components.AppTextField(letter, { letter = it }, label = { Text("✉️ Letter to the future (optional)") }, modifier = Modifier.fillMaxWidth())
                if (letter.isNotBlank()) {
                    val u = if (sealedUntil > 0) Instant.ofEpochMilli(sealedUntil).atZone(ZoneId.systemDefault()).toLocalDate() else null
                    TextButton(onClick = { showSealDate = true }) { Text(if (u == null) "Seal until…" else "Unlocks ${u.dayOfMonth} ${u.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${u.year}") }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Colour — unified picker (R58).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Colour", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                com.todocompanion.app.ui.components.AppColorPicker(current = color, onPick = { color = it ?: color })
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
                com.todocompanion.app.ui.components.AppTextField(momentDraft, { momentDraft = it }, label = { Text("Log a moment, a note, or a gift") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    // #27 gift ledger — a gift is a moment with a marker, surfaced back next year in the prep task.
                    TextButton(enabled = momentDraft.isNotBlank(), onClick = {
                        vm.logOccasionGift(existing, momentDraft)
                        momentsLocal = (momentsLocal + com.todocompanion.app.domain.Moment(LocalDate.now().toEpochDay(), com.todocompanion.app.domain.Moments.GIFT_PREFIX + momentDraft.trim())).sortedByDescending { it.d }
                        momentDraft = ""
                    }) { Text("🎁 Gift") }
                    TextButton(enabled = momentDraft.isNotBlank(), onClick = {
                        vm.logOccasionMoment(existing, momentDraft)
                        momentsLocal = (momentsLocal + com.todocompanion.app.domain.Moment(LocalDate.now().toEpochDay(), momentDraft.trim())).sortedByDescending { it.d }
                        momentDraft = ""
                    }) { Text("Log moment") }
                }
                com.todocompanion.app.domain.Moments.lastGift(existing.copy(momentsJson = com.todocompanion.app.domain.Moments.encode(momentsLocal)))?.let { (gd, g) ->
                    Text("Last gift: $g (${gd.year})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            // #21 Date Lab — pure offline date intelligence for any saved occasion.
            if (existing != null) {
                var labOpen by remember { mutableStateOf(false) }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { labOpen = !labOpen }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(if (labOpen) "Hide Date Lab ▲" else "🔬 Date Lab ▼") }
                if (labOpen) {
                    val now = LocalDate.now()
                    val (season, seasonDate) = com.todocompanion.app.domain.DateLab.nextSeasonMarker(now)
                    val lines = buildList {
                        add("Moon on that date: ${com.todocompanion.app.domain.DateLab.moonPhase(d)}")
                        if (yearKnown) {
                            add("Mars age: ${"%.1f".format(com.todocompanion.app.domain.DateLab.marsAge(d, now))} Mars years")
                            add("Jupiter age: ${"%.2f".format(com.todocompanion.app.domain.DateLab.jupiterAge(d, now))} Jupiter years")
                        }
                        add("Next $season: ${seasonDate.dayOfMonth} ${seasonDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${seasonDate.year}")
                        val p100 = com.todocompanion.app.domain.DateLab.datePlus(now, 100)
                        add("100 days from today: ${p100.dayOfMonth} ${p100.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${p100.year}")
                    }
                    lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 1.dp)) }
                }
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
    if (showSealDate) DateOnlyPickerDialog(if (sealedUntil > 0) sealedUntil else LocalDate.now().plusYears(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), { showSealDate = false }) { m -> sealedUntil = m; showSealDate = false }
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

/** R48 — extensive filter state for the Occasions list. Empty/false everywhere = show all. */
private data class OccasionFilter(
    val types: Set<String> = emptySet(),
    val category: String? = null,
    val favouritesOnly: Boolean = false,
    val withCadence: Boolean = false,
    val countUp: Boolean? = null,
    val windowDays: Int = 0,
) {
    val active: Boolean get() = types.isNotEmpty() || category != null || favouritesOnly || withCadence || countUp != null || windowDays > 0
    fun matches(c: CountdownEntity, today: LocalDate): Boolean {
        if (types.isNotEmpty() && c.eventType !in types) return false
        if (category != null && !c.category.equals(category, ignoreCase = true)) return false
        if (favouritesOnly && !c.favorite) return false
        if (withCadence && c.keepInTouchDays <= 0) return false
        if (countUp != null && c.countUp != countUp) return false
        if (windowDays > 0) { val d = LifeEvent.daysUntil(c, today); if (d < 0 || d > windowDays) return false }
        return true
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OccasionFilterSheet(current: OccasionFilter, items: List<CountdownEntity>, onApply: (OccasionFilter) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var f by remember { mutableStateOf(current) }
    val categories = remember(items) { items.map { it.category.trim() }.filter { it.isNotBlank() }.distinct().sorted() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text("Filter occasions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("Type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LifeEvent.EventType.entries.forEach { t ->
                    val on = t.name in f.types
                    FilterChip(selected = on, onClick = { f = f.copy(types = if (on) f.types - t.name else f.types + t.name) }, label = { Text("${t.emoji} ${t.label}") })
                }
            }
            if (categories.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Category", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = f.category == null, onClick = { f = f.copy(category = null) }, label = { Text("Any") })
                    categories.forEach { cat -> FilterChip(selected = f.category == cat, onClick = { f = f.copy(category = cat) }, label = { Text(cat) }) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Within", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Any", 7 to "1 week", 30 to "1 month", 90 to "3 months", 365 to "1 year").forEach { (n, lbl) ->
                    FilterChip(selected = f.windowDays == n, onClick = { f = f.copy(windowDays = n) }, label = { Text(lbl) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Direction", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = f.countUp == null, onClick = { f = f.copy(countUp = null) }, label = { Text("Any") })
                FilterChip(selected = f.countUp == false, onClick = { f = f.copy(countUp = false) }, label = { Text("Countdown") })
                FilterChip(selected = f.countUp == true, onClick = { f = f.copy(countUp = true) }, label = { Text("Count-up") })
            }
            Spacer(Modifier.height(4.dp))
            EditorSwitch("Favourites only", f.favouritesOnly) { f = f.copy(favouritesOnly = it) }
            EditorSwitch("Has a keep-in-touch cadence", f.withCadence) { f = f.copy(withCadence = it) }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { f = OccasionFilter() }) { Text("Clear all") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onApply(f); onDismiss() }) { Text("Apply", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** R48 — a per-occasion details sheet on long-press: the person's life-in-weeks, their facts, the Date Lab,
 *  moments and milestones — moved off the top of the list so the list itself stays occasion-first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OccasionDetailsSheet(c: CountdownEntity, today: LocalDate, onDismiss: () -> Unit, onEdit: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val next = LifeEvent.nextOccurrence(c, today)
    val (count, unitLabel) = LifeEvent.displayCount(c, today)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val face = rememberFace(c.photoBase64)
                if (face != null && !c.locked) Image(face, null, Modifier.size(44.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                else Text(c.emoji ?: LifeEvent.type(c).emoji, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(LifeEvent.displayName(c), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("${if (c.countUp) "since " else ""}${next.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${next.dayOfMonth} ${next.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${next.year} · $count $unitLabel${if (c.countUp) " ago" else " left"}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onEdit) { Text("Edit") }
            }
            val chips = listOfNotNull(LifeEvent.ageChip(c, today), LifeEvent.zodiac(c), c.category.takeIf { it.isNotBlank() })
            if (chips.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { chip -> Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f)) { Text(chip, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) } }
            }
            // Keep-in-touch cadence status (relationship-upkeep loop), when a cadence is set.
            com.todocompanion.app.domain.Moments.cadenceLine(c, today)?.let { line ->
                val overdue = com.todocompanion.app.domain.Moments.cadenceOverdue(c, today)
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(10.dp),
                    color = if (overdue) MaterialTheme.colorScheme.errorContainer.copy(alpha = .5f) else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f)) {
                    Text("${if (overdue) "🔔" else "💬"}  $line", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                }
            }
            // Life in weeks — for a birthday with a known year.
            if (LifeEvent.type(c) == LifeEvent.EventType.BIRTHDAY && c.yearKnown) {
                Spacer(Modifier.height(12.dp)); LifeInWeeksCard(c, today, 0)
            }
            // Facts
            Spacer(Modifier.height(12.dp))
            Text("Facts", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            listOfNotNull(
                LifeEvent.chineseZodiac(c)?.let { "Chinese zodiac: $it" },
                LifeEvent.lifePath(c)?.let { "Life-path number: $it" },
                LifeEvent.dayOfWeekBorn(c)?.let { "Born on a $it" },
                LifeEvent.goldenBirthday(c)?.let { (age, dt) -> "Golden birthday: turns $age in ${dt.year}" },
                LifeEvent.nextRoundDayMilestone(c)?.let { (n, dt) -> "${"%,d".format(n)} days old on ${dt.dayOfMonth} ${dt.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${dt.year}" },
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 1.dp)) }
            // Date Lab
            Spacer(Modifier.height(10.dp))
            Text("Date Lab", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val d = Instant.ofEpochMilli(c.targetMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            val (season, seasonDate) = com.todocompanion.app.domain.DateLab.nextSeasonMarker(today)
            listOfNotNull(
                "Moon on that date: ${com.todocompanion.app.domain.DateLab.moonPhase(d)}",
                if (c.yearKnown) "Mars age: ${"%.1f".format(com.todocompanion.app.domain.DateLab.marsAge(d, today))} · Jupiter age: ${"%.2f".format(com.todocompanion.app.domain.DateLab.jupiterAge(d, today))}" else null,
                "Next $season: ${seasonDate.dayOfMonth} ${seasonDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${seasonDate.year}",
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 1.dp)) }
            // Moments summary
            val moments = com.todocompanion.app.domain.Moments.parse(c)
            if (moments.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Moments (${moments.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                moments.take(5).forEach { m ->
                    val dd = LocalDate.ofEpochDay(m.d)
                    Text("• ${m.n}  — ${dd.dayOfMonth} ${dd.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${dd.year}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 1.dp))
                }
            }
            // Get-to-know-them: a bundled, no-LLM question that rotates daily — for a person occasion.
            if (c.personName.isNotBlank() || LifeEvent.type(c) == LifeEvent.EventType.BIRTHDAY) {
                Spacer(Modifier.height(10.dp))
                Text("Get to know them", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(com.todocompanion.app.domain.KnowThem.questionFor(c, today), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            // A gentle finite-time reflection — the same one the daily nudge would show today.
            Spacer(Modifier.height(12.dp))
            Text(com.todocompanion.app.domain.Almanac.reflection(today), style = MaterialTheme.typography.bodySmall,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f))
            Spacer(Modifier.height(16.dp))
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Button(onClick = { shareOccasionCard(ctx, c, today) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Share card")
                }
                TextButton(onClick = { shareOccasion(ctx, c) }) { Text("As text") }
                TextButton(onClick = onEdit) { Text("Edit", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** R52 — render a personalised occasion card (PNG) on-device and offer it via the OS share sheet. */
private fun shareOccasionCard(ctx: android.content.Context, c: CountdownEntity, today: LocalDate) {
    runCatching {
        val bmp = com.todocompanion.app.util.OccasionCardRenderer.render(c, today)
        val safeName = (c.personName.ifBlank { c.title }).ifBlank { "occasion" }.filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(' ', '-').take(40).ifBlank { "occasion" }
        val res = com.todocompanion.app.util.ProgressCard.saveAndShareUri(ctx, bmp, "$safeName-card.png")
        val uri = res.shareUri
        if (uri != null) com.todocompanion.app.util.ProgressCard.share(ctx, uri)
        else android.widget.Toast.makeText(ctx, "Couldn't build the card.", android.widget.Toast.LENGTH_SHORT).show()
    }.onFailure {
        android.widget.Toast.makeText(ctx, "Couldn't build the card.", android.widget.Toast.LENGTH_SHORT).show()
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
