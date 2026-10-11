package app.parley.ui.home

import app.parley.ui.people.rememberWorkResults
import app.parley.ui.people.workResultsSection
import app.parley.ui.Destination
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.view.textclassifier.TextClassifier
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ripple
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.common.KeypadNumberActions
import androidx.compose.ui.graphics.vector.ImageVector
import app.parley.ui.ParleyType
import app.parley.ui.EmptyState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.insert
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonAddAlt
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.DialText
import app.parley.common.KeypadLayout
import app.parley.common.NumberText
import app.parley.common.calls.PressOrder
import app.parley.ui.Routes
import app.parley.ui.keypadKey
import kotlinx.coroutines.awaitCancellation
import app.parley.ui.ParleySheet
import app.parley.ui.showMessage
import app.parley.ui.ParleyListItem
import androidx.compose.ui.semantics.heading

/** Whether a hardware keypad (flip phones) or keyboard (QWERTY phones) is available and open. */
internal fun hardwareKeysAvailable(c: Configuration): Boolean =
    (c.keyboard == Configuration.KEYBOARD_12KEY || c.keyboard == Configuration.KEYBOARD_QWERTY) &&
        c.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES

/** Second row of letters on a key for the chosen alphabet; none for Latin only. */
internal fun localLetters(layout: KeypadLayout, digit: Char): String = layout.lettersFor(digit)

internal fun TextFieldState.insertAtCursor(text: String) = edit {
    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    replace(start, end, text)
    selection = TextRange(start + text.length)
}

internal fun TextFieldState.deleteBeforeCursor() = edit {
    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    when {
        start != end -> { delete(start, end); selection = TextRange(start) }
        start > 0 -> { delete(start - 1, start); selection = TextRange(start - 1) }
    }
}

internal fun TextFieldState.deleteAfterCursor() = edit {
    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    when {
        start != end -> { delete(start, end); selection = TextRange(start) }
        start < length -> delete(start, start + 1)
    }
}

/** Pasted or cut text keeps only what can be dialled. */
private object DialInputFilter : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val text = asCharSequence().toString()
        // Deleting (cut) needs no cleaning, and keeps letters typed on a hardware keyboard for name search.
        if (text.length < originalText.length) return
        val clean = DialText.sanitize(text)
        if (clean != text) {
            replace(0, length, clean)
            selection = TextRange(clean.length)
        }
    }
}

/** Shows the number formatted for the country ("06 12 34 56 78") while editing the plain digits. */
private class FormattedNumber(private val countryIso: String) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val raw = asCharSequence().toString()
        if (raw.length < 4 || raw.any { !(it.isDigit() || it == '+') }) return
        val formatted = PhoneNumberUtils.formatNumber(raw, countryIso) ?: return
        val inserts = DialText.formattingInserts(raw, formatted) ?: return
        var offset = 0
        for ((at, ch) in inserts) {
            insert(at + offset, ch.toString())
            offset++
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun NumberField(state: TextFieldState, countryIso: String, modifier: Modifier) {
    val long = state.text.length > 14
    val style = ParleyType.typedDigits.copy(
        fontSize = if (long) 24.sp else 32.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val output = remember(countryIso) { FormattedNumber(countryIso) }
    val numberLabel = stringResource(R.string.keypad_number_description)
    // Tapping places the cursor and long-press offers Paste, but the on-screen keyboard never opens: the keypad
    // below (or a hardware keypad) is the keyboard.
    InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }) {
        BasicTextField(
            state = state,
            modifier = modifier.semantics { contentDescription = numberLabel.format(state.text) },
            textStyle = style,
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = DialInputFilter,
            outputTransformation = output,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, showKeyboardOnFocus = false),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

private enum class ClipHint { NONE, TEXT, NUMBER }

/**
 * Whether the clipboard may hold a number, without reading it: only the clip description is checked (no
 * "pasted from your clipboard" notice on Android 12+). The text is read only when the chip is tapped.
 */
@Composable
internal fun PasteChip(countryIso: String, onPaste: (String) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val cm = remember { context.getSystemService(ClipboardManager::class.java) }
    var hint by remember { mutableStateOf(ClipHint.NONE) }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) hint = clipHint(cm) }
    DisposableEffect(cm) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener { hint = clipHint(cm) }
        cm?.addPrimaryClipChangedListener(listener)
        onDispose { cm?.removePrimaryClipChangedListener(listener) }
    }
    if (hint == ClipHint.NONE) return
    AssistChip(
        modifier = Modifier.padding(top = 12.dp),
        onClick = {
            val text = runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull().orEmpty()
            val number = NumberText.find(text, countryIso).firstOrNull()?.raw?.let(DialText::sanitize)
            if (number.isNullOrEmpty()) showMessage(context, res.getString(R.string.keypad_no_clip_number))
            else onPaste(number)
        },
        label = { Text(stringResource(if (hint == ClipHint.NUMBER) R.string.keypad_paste_number else R.string.action_paste)) },
        leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) },
    )
}

private fun clipHint(cm: ClipboardManager?): ClipHint = try {
    val d = if (cm?.hasPrimaryClip() == true) cm.primaryClipDescription else null
    when {
        d == null || !d.hasMimeType("text/*") -> ClipHint.NONE
        Build.VERSION.SDK_INT >= 31 && d.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE ->
            if (d.getConfidenceScore(TextClassifier.TYPE_PHONE) >= 0.5f) ClipHint.NUMBER else ClipHint.NONE
        else -> ClipHint.TEXT
    }
} catch (_: Exception) {
    ClipHint.NONE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImeiSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ParleySheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.keypad_imei_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(
                stringResource(R.string.keypad_imei_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button({
                runCatching { context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
                onDismiss()
            }) { Text(stringResource(R.string.keypad_open_about)) }
            TextButton(onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    }
}

/**
 * One keypad key: the digit, its Latin letters, and optionally a second row with the chosen alphabet's letters.
 * The key grows with the system font size (the digit up to 1.5×, the letters fully) so both letter rows stay
 * readable at 200 %.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DialKey(
    digit: String, letters: String, local: String, modifier: Modifier = Modifier, deferPress: Boolean = false,
    pressOrder: PressOrder? = null,
    onPress: () -> Unit, onRelease: (afterMs: Long) -> Unit, onLong: ((typedThisTouch: Boolean) -> Unit)?,
) {
    val fontScale = LocalDensity.current.fontScale
    // Large, light digits with the letters (or the long-press character) beneath, no key backgrounds.
    val digitSize = ((if (digit == "*") 38f else 34f) * minOf(fontScale, 1.5f) / fontScale).sp
    val res = LocalResources.current
    // What shows under the digit: the Latin letters, or the key's long-press character (",", "+", ";").
    val under = when (digit) {
        "*" -> ","
        "#" -> ";"
        else -> letters
    }
    Column(
        modifier
            .padding(horizontal = 4.dp)
            .heightIn(min = 66.dp)
            // Typed and sounded on touch; slide off to cancel the long-press; keys roll over.
            // A soft round ripple around the key's centre instead of a filled key shape.
            .keypadKey(
                onPress = onPress, onToneStop = onRelease, onLongPress = onLong, longPressLabel = longPressLabel(res, digit),
                deferPress = deferPress, indication = keyRipple,
                // Digits keep their order, and only a downward drag (a fold) drops a waiting press.
                pressOrder = pressOrder, foldDownOnly = deferPress,
            )
            .padding(vertical = 2.dp)
            .semantics { contentDescription = keyDescription(res, digit, letters) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(digit, style = ParleyType.keypadDigit(digitSize), color = MaterialTheme.colorScheme.onSurface)
        val subStyle = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp)
        if (digit == "1") {
            Icon(Icons.Rounded.Voicemail, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (under.isNotEmpty() || local.isEmpty()) {
            Text(under, style = subStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        }
        if (local.isNotEmpty()) {
            Text(local, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
        }
    }
}

/** The keys' press feedback: a round ripple, a little wider than the digit and its letters. */
private val keyRipple = ripple(bounded = false, radius = 38.dp)

private fun longPressLabel(res: Resources, digit: String): String? = when (digit) {
    "0" -> res.getString(R.string.keypad_long_plus)
    "1" -> res.getString(R.string.action_call_voicemail)
    "*" -> res.getString(R.string.keypad_long_pause)
    "#" -> res.getString(R.string.keypad_long_wait)
    else -> if (digit[0] in '2'..'9') res.getString(R.string.label_speed_dial) else null
}

/** What TalkBack reads for a key: "2, A B C", "1, voicemail", "star", "pound". */
private fun keyDescription(res: Resources, digit: String, letters: String): String = when (digit) {
    "*" -> res.getString(app.parley.ui.R.string.ui_key_star)
    "#" -> res.getString(app.parley.ui.R.string.ui_key_pound)
    "1" -> res.getString(R.string.keypad_key_voicemail)
    else -> if (letters.isEmpty()) digit else res.getString(R.string.format_comma_pair, digit, letters.toList().joinToString(" "))
}

/** One name and icon per number action, the same in the results rows and the chips. */
private fun numberActionLabel(action: KeypadNumberActions.Action): Int = when (action) {
    KeypadNumberActions.Action.MESSAGE_OR_CALL -> R.string.contact_page_sec_messengers
    KeypadNumberActions.Action.CREATE_CONTACT -> R.string.keypad_create_contact
    KeypadNumberActions.Action.ADD_TO_CONTACT -> R.string.recents_add_to_contact
    KeypadNumberActions.Action.SAVE_TEMPORARY -> R.string.action_save_temporary_contact
}

private fun numberActionIcon(action: KeypadNumberActions.Action): ImageVector = when (action) {
    KeypadNumberActions.Action.MESSAGE_OR_CALL -> Icons.AutoMirrored.Rounded.Chat
    KeypadNumberActions.Action.CREATE_CONTACT -> Icons.Rounded.PersonAdd
    KeypadNumberActions.Action.ADD_TO_CONTACT -> Icons.Rounded.PersonAddAlt
    KeypadNumberActions.Action.SAVE_TEMPORARY -> Icons.Rounded.AutoDelete
}

/** A number action as a results row (no contact matches); "Message or call on…" names the apps it reaches. */
@Composable
internal fun NumberActionRow(action: KeypadNumberActions.Action, onClick: () -> Unit) {
    ParleyListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(stringResource(numberActionLabel(action))) },
        supportingContent = if (action == KeypadNumberActions.Action.MESSAGE_OR_CALL) ({ Text(stringResource(R.string.reach_apps_line)) }) else null,
        leadingContent = { Icon(numberActionIcon(action), null) },
    )
}

/** The number actions as a compact chip row, shown while contacts fill the results. */
@Composable
internal fun NumberActionChips(actions: List<KeypadNumberActions.Action>, onAction: (KeypadNumberActions.Action) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            AssistChip(
                onClick = { onAction(action) },
                label = { Text(stringResource(numberActionLabel(action))) },
                leadingIcon = { Icon(numberActionIcon(action), null) },
            )
        }
    }
}

/** Keypad search from the header: every contact (and visible private contact) matching a name or number. */
@Composable
internal fun KeypadContactSearch(vm: AppViewModel, keypad: KeypadViewModel, query: String, open: (Destination) -> Unit) {
    val q = query.trim()
    // Searched in the view model over names folded once per contacts change, off the main thread.
    LaunchedEffect(query) { keypad.searchQuery.value = query }
    if (q.isEmpty()) {
        EmptyState(Icons.Rounded.Search, stringResource(R.string.action_search_contacts), stringResource(R.string.keypad_search_body))
        return
    }
    val result by keypad.search.collectAsStateWithLifecycle()
    val r = result ?: return
    val found = r.contacts
    val foundVault = r.vault
    // The work profile's matches, read-only, after everyone else.
    val work = rememberWorkResults(q)
    val nothing = found.isEmpty() && foundVault.isEmpty() && work.isEmpty()
    // The results of the query before this one stay up while the new search runs.
    if (nothing && r.query == q) {
        // No match: offer to save what was typed as a new contact.
        EmptyState(
            Icons.Rounded.Search, stringResource(R.string.keypad_no_match, q),
            action = stringResource(R.string.keypad_create_contact),
            onAction = { open(if (q.any { it.isLetter() }) Routes.edit(name = q) else Routes.edit(phone = q)) },
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        // Private contacts look like every contact (a lock on the photo) and open the same page.
        items(foundVault, key = { "v" + it.id }) { row ->
            ContactRow(row, actions = true, onCall = { n -> vm.requestCall(n, row.displayName) }) { open(Routes.contact(row.id)) }
        }
        items(found, key = { it.id }) { c ->
            ContactRow(c, actions = true, onCall = { n -> vm.requestCall(n, c.displayName) }) { open(Routes.contact(c.id)) }
        }
        workResultsSection(work) { n, name -> vm.requestCall(n, name) }
    }
}
