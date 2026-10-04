package app.parley.ui.contact

import app.parley.common.cards.SignedCards
import app.parley.ui.Clipboard
import app.parley.ui.people.cards.CardArrivalNotes
import android.content.Context
import android.content.res.Resources
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.NumberText
import app.parley.common.PhoneEntry
import app.parley.common.people.DuplicateHit
import app.parley.common.people.DuplicateLookup
import app.parley.common.people.PasteParser
import app.parley.common.people.PasteParser.Kind
import app.parley.common.people.PasteParser.Label
import app.parley.common.ux.Tips
import app.parley.ui.Bidi
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import app.parley.ui.common.CoachMark
import app.parley.ui.DataL10n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import app.parley.common.extras.PendingSlot

/**
 * Text shared to Parley for "Make a contact from this text", handed to the new contact's editor in memory only (like
 * "Save all…" hands its text to Add several numbers) and read once. L8: it is bound to the editor it was handed to
 * (a random id in that route) and expires after a few minutes, so a later "paste" route, from Parley or another app,
 * never gets stale text.
 */
object PasteInbox {
    private const val TTL_MS = 10 * 60_000L
    private val slot = PendingSlot<String>(TTL_MS)

    /** Holds [t]; the returned id goes into the editor's route. */
    fun put(t: String, now: Long = System.currentTimeMillis()): String {
        val id = UUID.randomUUID().toString()
        slot.put(id, now, t.take(PasteParser.MAX_TEXT))
        return id
    }

    /** The text handed over with [id], once, while it's fresh. */
    fun take(id: String?, now: Long = System.currentTimeMillis()): String? = slot.take(id, now)
}

/**
 * The system's on-device text classifier (no network: the same one that finds links in selected text), asked where
 * the text holds addresses, phone numbers, emails and links. Parley's own rules use these where theirs say nothing.
 * Empty when the phone has no classifier or it fails.
 */
internal object PasteHints {
    suspend fun of(context: Context, text: String): List<PasteParser.Hint> = withContext(Dispatchers.Default) {
        runCatching {
            val tc = context.getSystemService(TextClassificationManager::class.java)?.textClassifier
            if (tc == null || tc === TextClassifier.NO_OP) return@runCatching emptyList()
            val t = text.take(minOf(PasteParser.MAX_TEXT, tc.maxGenerateLinksTextLength))
            val entities = listOf(TextClassifier.TYPE_ADDRESS, TextClassifier.TYPE_PHONE, TextClassifier.TYPE_EMAIL, TextClassifier.TYPE_URL)
            val request = TextLinks.Request.Builder(t)
                .setEntityConfig(TextClassifier.EntityConfig.createWithExplicitEntityList(entities))
                .build()
            tc.generateLinks(request).links.mapNotNull { link ->
                if (link.entityCount == 0) return@mapNotNull null
                val type = when (link.getEntity(0)) {
                    TextClassifier.TYPE_ADDRESS -> PasteParser.HintType.ADDRESS
                    TextClassifier.TYPE_PHONE -> PasteParser.HintType.PHONE
                    TextClassifier.TYPE_EMAIL -> PasteParser.HintType.EMAIL
                    TextClassifier.TYPE_URL -> PasteParser.HintType.URL
                    else -> return@mapNotNull null
                }
                PasteParser.Hint(link.start, link.end, type)
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * The people read from pasted text and what is ticked, for as long as the editor is open (rotation included). Kept in
 * memory only, never in saved state: the text may belong to a private contact.
 */
class PasteViewModel : ViewModel() {
    var cards by mutableStateOf<List<PasteParser.Card>>(emptyList())
        private set
    var chosen by mutableIntStateOf(0)
        private set
    var ticked by mutableStateOf<Set<Int>>(emptySet())
        private set
    var reading by mutableStateOf(false)
        private set

    /** The shared text was read (once, not again after rotation). */
    var sharedRead = false

    /** The last text read, when it may hold a signed Parley card (checked like a scanned or opened card). */
    var signedText by mutableStateOf<String?>(null)
        private set

    val card: PasteParser.Card? get() = cards.getOrNull(chosen)
    val picked: List<PasteParser.Field> get() = card?.fields?.filterIndexed { i, _ -> i in ticked }.orEmpty()

    /** Reads [text]; [onNone] when it holds no contact details. */
    fun read(context: Context, text: String, region: String, onNone: () -> Unit) {
        if (reading) return
        reading = true
        if (SignedCards.mayHold(text)) signedText = text
        viewModelScope.launch {
            val hints = PasteHints.of(context.applicationContext, text)
            val found = withContext(Dispatchers.Default) { PasteParser.parse(text, region, hints) }
            reading = false
            if (found.isEmpty()) return@launch onNone()
            cards = found
            choose(0)
        }
    }

    fun choose(i: Int) {
        chosen = i
        ticked = card?.fields?.indices?.filter { card?.fields?.get(it)?.suggested == true }?.toSet().orEmpty()
    }

    fun toggle(i: Int) {
        ticked = if (i in ticked) ticked - i else ticked + i
    }

    fun close() {
        cards = emptyList()
        ticked = emptySet()
    }
}

/**
 * "Paste details" at the top of a new contact: reads the clipboard only when tapped, then shows what it found field by
 * field. Nothing is saved from here: [onFill] fills the form, [onAddTo] continues in an existing contact's editor (a
 * negative id is a private contact). [shared] is text shared to Parley, read at once.
 */
@Composable
internal fun PasteDetailsEntry(
    vm: AppViewModel,
    shared: String?,
    onFill: (List<PasteParser.Field>) -> Unit,
    onAddTo: (Long, List<PasteParser.Field>) -> Unit,
) {
    val paste: PasteViewModel = viewModel()
    val context = LocalContext.current
    val nothing = stringResource(R.string.paste_nothing)
    val none = stringResource(R.string.paste_none_found)
    fun read(text: String?) {
        if (text.isNullOrBlank()) vm.toast(nothing) else paste.read(context, text, vm.countryIso) { vm.toast(none) }
    }
    LaunchedEffect(shared) {
        if (shared != null && !paste.sharedRead) {
            paste.sharedRead = true
            read(shared)
        }
    }
    Column {
        val busy = stringResource(R.string.paste_reading)
        AssistChip(
            onClick = { read(Clipboard.readText(context, PasteParser.MAX_TEXT)) },
            label = { Text(stringResource(R.string.paste_details)) },
            leadingIcon = {
                if (paste.reading) {
                    CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = busy }, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp))
                }
            },
        )
        CoachMark(Tips.PASTE_DETAILS, stringResource(R.string.paste_tip))
        // A pasted card signed by someone you know: "Ana sent an updated card", as for a scanned or opened one.
        CardArrivalNotes(vm, paste.signedText)
    }
    if (paste.card != null) PastePreview(vm, paste, onFill, onAddTo)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PastePreview(
    vm: AppViewModel,
    paste: PasteViewModel,
    onFill: (List<PasteParser.Field>) -> Unit,
    onAddTo: (Long, List<PasteParser.Field>) -> Unit,
) {
    val card = paste.card ?: return
    val res = LocalContext.current.resources
    val hit = existingMatch(vm, paste.picked)
    ParleySheet(
        onDismissRequest = paste::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = stringResource(R.string.paste_found_title),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.paste_found_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (paste.cards.size > 1) {
                Text(
                    pluralStringResource(R.plurals.paste_people, paste.cards.size, paste.cards.size), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
                )
                FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    paste.cards.forEachIndexed { i, c ->
                        FilterChip(
                            selected = i == paste.chosen, onClick = { paste.choose(i) },
                            label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
                        )
                    }
                }
            }
            card.fields.forEachIndexed { i, f ->
                val on = i in paste.ticked
                ParleyListItem(
                    modifier = Modifier.toggleable(on, role = Role.Checkbox) { paste.toggle(i) },
                    overlineContent = { Text(typeLabel(res, f)) },
                    headlineContent = { Text(shown(res, f), maxLines = if (f.kind == Kind.NOTE) 6 else 3, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Checkbox(on, onCheckedChange = null) },
                )
            }
            if (hit != null) {
                val private = hit.contact.id < 0
                Text(
                    stringResource(if (private) R.string.dup_private_has else R.string.dup_has, hit.contact.displayName, DataL10n.ltr(hit.matched)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
            ) {
                val picked = paste.picked
                TextButton(paste::close) { Text(stringResource(R.string.main_cancel)) }
                if (hit != null) {
                    TextButton({ paste.close(); onFill(picked) }, enabled = picked.isNotEmpty()) { Text(stringResource(R.string.paste_new_contact)) }
                    Button({ paste.close(); onAddTo(hit.contact.id, picked) }, enabled = picked.isNotEmpty()) {
                        val first = hit.contact.displayName.substringBefore(' ')
                        Text(stringResource(R.string.paste_add_to, first), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Button({ paste.close(); onFill(picked) }, enabled = picked.isNotEmpty()) { Text(stringResource(R.string.paste_fill)) }
                }
            }
        }
    }
}

/**
 * A contact (or a private one, unless they are hidden) that already has one of the ticked numbers or emails, so the
 * details can go to them instead of a second contact.
 */
@Composable
private fun existingMatch(vm: AppViewModel, picked: List<PasteParser.Field>): DuplicateHit? {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val vault by vm.c.vault.contacts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lookup = remember(contacts) { contacts?.let { DuplicateLookup(it) } }
    val vaultLookup = remember(vault, settings.hideVault) {
        if (settings.hideVault) {
            null
        } else {
            DuplicateLookup(vault.map { v -> ContactSummary(-v.id, "", v.name, null, false, v.numbers.map { PhoneEntry(it, 2, null) }) })
        }
    }
    val phones = picked.filter { it.kind == Kind.PHONE }.map { it.value.substringBefore(',') }
    val emails = picked.filter { it.kind == Kind.EMAIL }.map { it.value }
    var hit by remember { mutableStateOf<DuplicateHit?>(null) }
    LaunchedEffect(lookup, vaultLookup, phones, emails) {
        hit = withContext(Dispatchers.Default) { lookup?.find("", phones, emails) ?: vaultLookup?.find("", phones, emails) }
    }
    return hit
}

@Suppress("CyclomaticComplexMethod") // One label per kind and type.
private fun typeLabel(res: Resources, f: PasteParser.Field): String = when (f.kind) {
    Kind.NAME -> res.getString(R.string.paste_type_name)
    Kind.ORGANISATION -> res.getString(R.string.paste_type_company)
    Kind.JOB_TITLE -> res.getString(R.string.paste_type_title)
    Kind.PHONE -> res.getString(
        when (f.label) {
            Label.MOBILE -> R.string.paste_type_mobile
            Label.WORK -> R.string.paste_type_work_phone
            Label.HOME -> R.string.paste_type_home_phone
            Label.MAIN -> R.string.paste_type_main
            Label.FAX -> R.string.paste_type_fax
            Label.OTHER, Label.NONE -> R.string.paste_type_phone
        },
    )
    Kind.EMAIL -> res.getString(
        when (f.label) {
            Label.WORK -> R.string.paste_type_work_email
            Label.HOME -> R.string.paste_type_personal_email
            else -> R.string.paste_type_email
        },
    )
    Kind.ADDRESS -> res.getString(if (f.label == Label.HOME) R.string.paste_type_home_address else R.string.paste_type_work_address)
    Kind.MAP_LINK -> res.getString(R.string.paste_type_map)
    Kind.WEBSITE -> res.getString(R.string.paste_type_website)
    Kind.PROFILE -> f.profile?.let { res.getString(R.string.paste_type_profile, it.service.label) } ?: res.getString(R.string.paste_type_website)
    Kind.BIRTHDAY -> res.getString(R.string.paste_type_birthday)
    Kind.NOTE -> res.getString(R.string.paste_type_note)
}

/** The value as the contact page will show it: numbers in international form (left to right), dates in the user's format. */
private fun shown(res: Resources, f: PasteParser.Field): String = when (f.kind) {
    Kind.PHONE -> {
        val number = NumberText.formatInternational(f.value.substringBefore(','))
        Bidi.ltr(f.extension?.let { res.getString(R.string.paste_extension, number, it) } ?: number)
    }
    Kind.EMAIL, Kind.WEBSITE, Kind.MAP_LINK -> Bidi.ltr(f.value)
    Kind.PROFILE -> f.profile?.display?.let(Bidi::ltr) ?: f.value
    Kind.BIRTHDAY -> birthday(f.value)
    else -> f.value
}

private fun birthday(date: String): String = runCatching {
    val locale = Locale.getDefault()
    if (date.startsWith("--")) {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMM")
        MonthDay.parse(date).format(DateTimeFormatter.ofPattern(pattern, locale))
    } else {
        LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
    }
}.getOrDefault(date)
