package app.parley.ui.family

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.ExperimentalComposeUiApi
import android.view.inputmethod.EditorInfo
import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.calls.ExpectedCallHints
import app.parley.common.ContactSummary
import app.parley.common.TextSearch
import app.parley.common.calls.ExpectedSource
import app.parley.common.calls.ExpectedWindow
import app.parley.common.calls.Helper
import app.parley.common.calls.Helpers
import app.parley.common.calls.SafeWord
import app.parley.common.calls.SafeWords
import app.parley.data.PhoneEnv
import app.parley.security.AppLock
import app.parley.security.VaultSession
import app.parley.ui.Avatar
import app.parley.ui.Bidi
import app.parley.ui.ConfirmDialog
import app.parley.ui.Destination
import app.parley.ui.LinkRow
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.PersonRow
import app.parley.ui.SegmentedGroup
import app.parley.ui.SettingsScaffold
import app.parley.ui.Spacing
import app.parley.ui.SwitchRow
import app.parley.ui.people.PeopleRoutes
import app.parley.ui.rowColors
import kotlinx.coroutines.launch

/** How long a confirmation counts for the safe word (seeing it, then changing it, is one visit). */
private const val CONFIRMED_FOR_MS = 60_000L

/**
 * Confirms it's the owner (fingerprint or screen lock, as the app lock does) before a safe word is shown or changed.
 * Without a screen lock there's nothing to confirm; a confirmation in the last minute counts.
 */
internal fun confirmItsYou(context: Context, onOk: () -> Unit) {
    val activity = context as? ComponentActivity ?: return
    if (VaultSession.recentlyAuthenticated(CONFIRMED_FOR_MS)) {
        onOk()
        return
    }
    AppLock.confirm(activity, activity.getString(R.string.safe_word_confirm)) { ok -> if (ok) onOk() }
}

/**
 * I4 on a label's page: "Family safe word", set or not. Seeing or changing it asks who it is first; the question and
 * answer are read from the sealed store only then, and never kept in saved state.
 */
@Composable
fun SafeWordSection(vm: AppViewModel, title: String) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val store = vm.c.familySafety
    val summary by store.summary.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { store.load() }
    val set = summary.safeWordLabels.any { it.trim() == title.trim() }
    var editing by remember { mutableStateOf<SafeWord?>(null) }
    var open by remember { mutableStateOf(false) }
    ParleyListItem(
        modifier = Modifier.clickable {
            confirmItsYou(context) {
                scope.launch {
                    editing = store.safeWord(title)
                    open = true
                }
            }
        },
        leadingContent = { Icon(Icons.Rounded.FamilyRestroom, null) },
        headlineContent = { Text(stringResource(R.string.safe_word_row)) },
        // Says what it is for the first time and every time (P18).
        supportingContent = { Text(stringResource(if (set) R.string.safe_word_row_set else R.string.safe_word_row_none)) },
        trailingContent = { Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
    if (open) {
        SafeWordDialog(
            label = title,
            initial = editing,
            onDismiss = { open = false; editing = null },
            onSave = { word ->
                open = false
                editing = null
                scope.launch {
                    val ok = store.setSafeWord(title, word)
                    val said = when {
                        !ok -> R.string.safe_word_failed
                        word == null -> R.string.safe_word_removed
                        else -> R.string.safe_word_saved
                    }
                    vm.toast(res.getString(said))
                }
            },
        )
    }
}

/** The question and answer for [label]; [onSave] with null removes them. */
@Composable
private fun SafeWordDialog(label: String, initial: SafeWord?, onDismiss: () -> Unit, onSave: (SafeWord?) -> Unit) {
    val defaultQuestion = stringResource(R.string.safe_word_question_hint)
    // Never in saved state: the answer lives only while the dialog does.
    var question by remember { mutableStateOf(initial?.question ?: defaultQuestion) }
    var answer by remember { mutableStateOf(initial?.answer.orEmpty()) }
    var shown by remember { mutableStateOf(false) }
    val cleaned = SafeWords.clean(question, answer)
    ConfirmDialog(
        title = stringResource(R.string.safe_word_dialog_title, label),
        text = null,
        confirmLabel = stringResource(R.string.main_save),
        onConfirm = { cleaned?.let(onSave) },
        onDismiss = onDismiss,
        confirmEnabled = cleaned != null,
        dismissLabel = stringResource(R.string.main_cancel),
        content = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text(stringResource(R.string.safe_word_dialog_intro), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    question, { question = it.take(SafeWords.MAX_QUESTION) }, singleLine = true,
                    label = { Text(stringResource(R.string.safe_word_question)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                // L3: a secret: a password field with no suggestions or autocorrect, and the keyboard told not to learn it.
                NoKeyboardLearning {
                    OutlinedTextField(
                        answer, { answer = it.take(SafeWords.MAX_ANSWER) }, singleLine = true,
                        label = { Text(stringResource(R.string.safe_word_answer)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton({ shown = !shown }) {
                                Icon(
                                    if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    stringResource(if (shown) R.string.safe_word_hide_answer else R.string.safe_word_show_answer),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (initial != null) {
                    TextButton({ onSave(null) }) { Text(stringResource(R.string.safe_word_remove), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
    )
}

/** Settings › Privacy & security › Family safe word: which labels have one, each a tap from its page. */
@Composable
fun SafeWordsScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit) {
    val store = vm.c.familySafety
    val summary by store.summary.collectAsStateWithLifecycle()
    val all by vm.everyone.collectAsStateWithLifecycle()
    val labels by produceState<List<String>?>(null, all) {
        store.load()
        value = runCatching { vm.c.people.labels.labels().map { it.title } }.getOrDefault(emptyList())
    }
    SettingsScaffold(stringResource(R.string.set_family_safe_word_title), back) {
        Text(
            stringResource(R.string.safe_words_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
        )
        val list = labels ?: return@SettingsScaffold
        if (list.isEmpty()) {
            Text(
                stringResource(R.string.safe_words_no_labels), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
            )
            TextButton({ open(PeopleRoutes.Labels) }, Modifier.padding(horizontal = Spacing.l)) { Text(stringResource(R.string.safe_words_open_labels)) }
            return@SettingsScaffold
        }
        SegmentedGroup(stringResource(R.string.safe_words_labels)) {
            list.forEach { t ->
                item(t) {
                    val set = summary.safeWordLabels.any { it.trim() == t.trim() }
                    LinkRow(t, stringResource(if (set) R.string.safe_words_set else R.string.safe_words_not_set), Icons.AutoMirrored.Rounded.Label) {
                        open(PeopleRoutes.label(t))
                    }
                }
            }
        }
    }
}

/**
 * Settings › Calls › Helpers (also from simple mode's setup): up to three people "Add my helper" can call into a
 * call. Private contacts can be helpers (their names show only outside discreet mode during calls).
 */
@Composable
fun HelpersScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = vm.c.familySafety
    val summary by store.summary.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { store.load() }
    val everyone by vm.everyone.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    val helpers = summary.helpers
    SettingsScaffold(stringResource(R.string.set_call_helpers_title), back) {
        Text(
            stringResource(R.string.helpers_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
        )
        SegmentedGroup {
            helpers.forEachIndexed { i, h ->
                item("h$i") {
                    PersonRow(
                        h.name, null,
                        colors = rowColors(),
                        supportingContent = {
                            val sep = stringResource(R.string.main_separator)
                            Text(if (h.private) Bidi.ltr(h.number) + sep + stringResource(R.string.helpers_private) else Bidi.ltr(h.number))
                        },
                        trailingContent = {
                            IconButton({ scope.launch { store.setHelpers(helpers.filterIndexed { j, _ -> j != i }) } }) {
                                Icon(Icons.Rounded.RemoveCircle, stringResource(R.string.helpers_remove, h.name), tint = MaterialTheme.colorScheme.error)
                            }
                        },
                    )
                }
            }
            item("add") {
                if (helpers.size < Helpers.MAX) {
                    LinkRow(stringResource(R.string.helpers_add), null, Icons.Rounded.PersonAdd) { picking = true }
                } else {
                    ParleyListItem(colors = rowColors(), headlineContent = { Text(stringResource(R.string.helpers_full)) })
                }
            }
        }
    }
    if (picking) {
        HelperPicker(everyone.orEmpty(), onDismiss = { picking = false }) { c, number ->
            picking = false
            val h = Helper(c.displayName, number, private = c.id < 0)
            scope.launch { store.setHelpers(Helpers.add(store.summary.value.helpers, h, PhoneEnv.countryIso(context))) }
        }
    }
}

/** Everyone with a number (private contacts too), searchable; a contact with several numbers asks which one. */
@Composable
private fun HelperPicker(contacts: List<ContactSummary>, onDismiss: () -> Unit, onPick: (ContactSummary, String) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    var numbersOf by remember { mutableStateOf<ContactSummary?>(null) }
    val shown = remember(q, contacts) {
        contacts.filter { c ->
            c.phones.any { p -> p.number.any { it.isDigit() } } && TextSearch.matches(q, c.displayName, c.phones.map { it.number })
        }.take(200)
    }
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.helpers_add)) },
        text = {
            Column {
                OutlinedTextField(
                    q, { q = it }, singleLine = true, label = { Text(stringResource(R.string.helpers_search)) }, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = Spacing.s)) {
                    items(shown, key = { it.id }) { c ->
                        ParleyListItem(
                            modifier = Modifier.clickable {
                                val numbers = c.phones.map { it.number }.filter { n -> n.any { it.isDigit() } }.distinct()
                                if (numbers.size == 1) onPick(c, numbers.first()) else numbersOf = c
                            },
                            colors = rowColors(),
                            leadingContent = { Avatar(c.displayName, c.photoUri, 36.dp) },
                            headlineContent = { Text(c.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
    numbersOf?.let { c ->
        ParleyDialog(
            onDismissRequest = { numbersOf = null },
            title = { Text(stringResource(R.string.helpers_pick_number)) },
            text = {
                Column {
                    c.phones.map { it.number }.filter { n -> n.any { it.isDigit() } }.distinct().forEach { n ->
                        ParleyListItem(
                            modifier = Modifier.clickable { numbersOf = null; onPick(c, n) }, colors = rowColors(),
                            headlineContent = { Text(Bidi.ltr(n)) },
                        )
                    }
                }
            },
            confirmButton = { TextButton({ numbersOf = null }) { Text(stringResource(R.string.main_cancel)) } },
        )
    }
}

/**
 * Text fields in [content] ask the keyboard not to learn what is typed (IME_FLAG_NO_PERSONALIZED_LEARNING), so a secret
 * never reaches its dictionary, suggestions or sync. Compose has no keyboard option for it, so the request is wrapped.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NoKeyboardLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            next.startInputMethod(
                PlatformTextInputMethodRequest { info: EditorInfo ->
                    request.createInputConnection(info).also { info.imeOptions = info.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING }
                },
            )
        },
        content = content,
    )
}

/** "Tue 6 Oct, 08:00 – 20:00" in the user's locale. */
internal fun windowText(context: Context, w: ExpectedWindow): String = DateUtils.formatDateRange(
    context, w.start, w.end,
    DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
)

/** What turned a window on, in words ("Note on Dentist", "Delivery QR code"); a private name not in discreet mode. */
@Composable
private fun sourceText(w: ExpectedWindow, discreet: Boolean): String = when (w.source) {
    ExpectedSource.NOTE -> w.shownLabel(discreet)?.let { stringResource(R.string.expected_from_note_on, it) } ?: stringResource(R.string.expected_from_note)
    ExpectedSource.TO_CALL -> stringResource(R.string.expected_from_to_call) + (w.number?.let { stringResource(R.string.main_separator) + Bidi.ltr(it) } ?: "")
    ExpectedSource.DELIVERY_QR -> stringResource(R.string.expected_from_delivery)
}

/**
 * Settings › Blocking & spam › "Expecting a call from your notes": one switch per kind of hint (off until accepted),
 * and the windows coming up, each removable.
 */
@Composable
fun ExpectedHintsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = vm.c.familySafety
    val summary by store.summary.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { store.load() }
    val now = remember { System.currentTimeMillis() }
    val upcoming = summary.windows.filter { it.end > now && summary.consents[it.source] == true }.sortedBy { it.start }
    val discreet = vm.settings.collectAsStateWithLifecycle().value.hideVault
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_expected_hints_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.expected_intro), style = MaterialTheme.typography.bodyMedium)
                listOf(
                    Triple(ExpectedSource.NOTE, R.string.expected_notes to R.string.expected_notes_body, Icons.AutoMirrored.Rounded.Notes),
                    Triple(ExpectedSource.TO_CALL, R.string.expected_to_call to R.string.expected_to_call_body, Icons.Rounded.PhoneInTalk),
                    Triple(ExpectedSource.DELIVERY_QR, R.string.expected_delivery to R.string.expected_delivery_body, Icons.Rounded.LocalShipping),
                ).forEach { (source, texts, icon) ->
                    SwitchRow(stringResource(texts.first), stringResource(texts.second), summary.consents[source] == true, icon) { on ->
                        scope.launch { store.setConsent(source, on) }
                    }
                }
                Text(
                    stringResource(R.string.expected_rules_apply), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs),
                )
                if (upcoming.isNotEmpty()) {
                    Text(
                        stringResource(R.string.expected_windows), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs),
                    )
                    upcoming.forEach { w ->
                        ParleyListItem(
                            colors = rowColors(),
                            leadingContent = { Icon(Icons.Rounded.HourglassTop, null) },
                            headlineContent = { Text(windowText(context, w)) },
                            supportingContent = { Text(sourceText(w, discreet)) },
                            trailingContent = {
                                IconButton({ scope.launch { store.removeWindow(w.source, w.key) } }) {
                                    Icon(Icons.Rounded.Close, stringResource(R.string.expected_remove))
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_done)) } },
    )
}

/** The one-time question for a kind of hint, at the app's root (shown when Parley is next open). */
@Composable
fun ExpectedCallOfferHost(vm: AppViewModel) {
    val offer by ExpectedCallHints.offer.collectAsStateWithLifecycle()
    val o = offer ?: return
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val w = o.window
    val until = DateUtils.formatDateTime(context, w.end, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL)
    val question = when (w.source) {
        ExpectedSource.NOTE -> res.getString(R.string.expected_offer_note, windowText(context, w))
        ExpectedSource.TO_CALL -> res.getString(R.string.expected_offer_to_call, Bidi.ltr(w.number.orEmpty()), until)
        ExpectedSource.DELIVERY_QR -> res.getString(R.string.expected_offer_delivery, until)
    }
    ConfirmDialog(
        title = stringResource(R.string.expected_offer_title),
        text = question + "\n\n" + stringResource(R.string.expected_offer_after),
        confirmLabel = stringResource(R.string.expected_offer_yes),
        onConfirm = { scope.launch { ExpectedCallHints.accept(vm.c, o) } },
        onDismiss = { scope.launch { ExpectedCallHints.decline(vm.c, o) } },
        dismissLabel = stringResource(R.string.expected_offer_no),
        icon = Icons.Rounded.HourglassTop,
    )
}
