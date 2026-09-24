package app.parley.messaging

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Public
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.parley.MainActivity
import app.parley.R
import app.parley.common.NumberText
import app.parley.container
import app.parley.data.NumberInfo
import app.parley.data.PhoneEnv
import app.parley.data.PlaceResult
import app.parley.ui.Bidi
import app.parley.ui.ParleyTheme
import app.parley.ui.common.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A small sheet over the current app for a phone number found in text (M3): "Call / Message with Parley" in text
 * selection menus, and text shared to Parley. Also hosts the "Message on…" sheet for places outside Parley's own
 * screens (missed-call notification, in-call screen).
 *
 * Nothing starts without a tap: several numbers show a picker, and every action is a button. The text is only used
 * to find numbers and is never stored. This activity doesn't handle `tel:` links (the keypad does).
 */
class NumberActionActivity : ComponentActivity() {
    // L1: the in-app language on Android 10-12 (Android 13+ applies per-app languages itself).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        app.parley.ui.AppLocale.override(this, newBase)
    }

    private sealed interface Stage {
        data object NoNumber : Stage
        /** M8: "Message a number" (tile, launcher shortcut): an empty field with Paste and the country. */
        data object Enter : Stage
        data class Pick(val found: List<NumberText.Found>) : Stage
        /** [raw] is the number as written in the text, re-read when the user picks another country (F19). */
        data class Actions(val number: String, val raw: String? = null) : Stage
        /** [accountId]: the SIM of the call the number comes from (missed-call notification), for its country. */
        data class Message(val number: String, val accountId: String? = null) : Stage
        data class Offer(val number: String, val via: String) : Stage
    }

    private var stage by mutableStateOf<Stage>(Stage.NoNumber)
    /** The call's open questions (dial guard, allowance, confirm, SIM), as in Parley itself. */
    private var pendingCall by mutableStateOf<app.parley.PendingCall?>(null)
    private var callSims by mutableStateOf<List<app.parley.common.SimAccount>>(emptyList())
    private val gate by lazy { app.parley.CallGate(container) }
    /** Read from disk before anything is shown: the defaults would mean no app lock and no secure screen. */
    private var appLock = true
    /** A chat with an unknown number was opened from here; offer a temporary contact when the user comes back. */
    private var awaitingReturn = false
    /** The selected, shared or pasted text, kept only while the sheet is open, for "Save all…" (M11). */
    private var sourceText: String? = null
    private var leftForChat = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        stage = initialStage(intent)
        // Secure until the settings say otherwise, so nothing is captured while they load.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            val settings = container.settings.current()
            appLock = settings.appLock
            if (!settings.secureScreen) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setContent {
                ParleyTheme(settings.themeMode, settings.amoledBlack, settings.dynamicColor, settings.density) {
                    Sheet()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        awaitingReturn = false
        stage = initialStage(intent)
    }

    override fun onPause() {
        super.onPause()
        if (awaitingReturn) leftForChat = true
    }

    override fun onResume() {
        super.onResume()
        if (!awaitingReturn || !leftForChat) return
        awaitingReturn = false
        leftForChat = false
        val chat = container.messaging.takeOpenedChat()
        if (chat == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val unknown = withContext(Dispatchers.IO) { ChatThenDecide.stillUnknown(container, chat) }
            if (unknown) stage = Stage.Offer(chat.number, chat.appLabel) else finish()
        }
    }

    private fun initialStage(intent: Intent): Stage {
        sourceText = null
        val text = when (intent.action) {
            MessageNumber.ACTION -> return Stage.Enter
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ACTION_MESSAGE_ON -> intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() }?.let { return Stage.Message(it, intent.getStringExtra(EXTRA_ACCOUNT_ID)) }
            else -> null
        }.orEmpty().take(MAX_TEXT)
        val found = NumberText.find(text, PhoneEnv.countryIso(this))
        if (found.size > 1) sourceText = text
        return when (found.size) {
            0 -> Stage.NoNumber
            1 -> Stage.Actions(found[0].e164 ?: found[0].raw, found[0].raw)
            else -> Stage.Pick(found)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Sheet() {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        pendingCall?.let { p ->
            app.parley.ui.common.CallQuestions(
                p, callSims, PhoneEnv.countryIso(this),
                onUpdate = { next -> pendingCall = next; if (next == null) finish() },
                onPlace = { number, simId, remember, confirmed -> place(number, simId, remember, confirmed, p.name) },
            )
            return
        }
        when (val s = stage) {
            is Stage.Offer -> OfferDialog(s)
            else -> ModalBottomSheet(onDismissRequest = { finish() }, sheetState = sheetState) {
                when (s) {
                    Stage.NoNumber -> Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.num_none_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.num_none_body), style = MaterialTheme.typography.bodyMedium)
                        TextButton({ finish() }) { Text(stringResource(R.string.main_close)) }
                    }
                    Stage.Enter -> EnterNumber()
                    is Stage.Pick -> PickNumber(s.found)
                    is Stage.Actions -> NumberActions(s.number, s.raw)
                    is Stage.Message -> MessageOnContent(s.number, s.accountId) { app -> afterLaunch(app != null) }
                    is Stage.Offer -> Unit
                }
            }
        }
    }

    private fun afterLaunch(messenger: Boolean) {
        if (messenger && container.messaging.openedChat.value != null) {
            // Stay (invisible behind the chat) to offer a temporary contact on return.
            awaitingReturn = true
            leftForChat = false
        } else {
            finish()
        }
    }

    @Composable
    private fun PickNumber(found: List<NumberText.Found>) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.num_choose), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (sourceText != null) {
                // M11: several numbers in the text can be saved together, after a review.
                ListItem(
                    headlineContent = { Text(pluralStringResource(R.plurals.num_save_all, found.size, found.size)) },
                    supportingContent = { Text(stringResource(R.string.num_save_all_body)) },
                    leadingContent = { Icon(Icons.Rounded.GroupAdd, null) },
                    modifier = Modifier.clickable { saveAll() },
                )
            }
            found.forEach { f ->
                ListItem(
                    headlineContent = { Text(Bidi.ltr(f.e164?.let(NumberText::formatInternational) ?: f.raw)) },
                    supportingContent = { Text(stringResource(R.string.num_in_text, f.raw)) },
                    modifier = Modifier.clickable { stage = Stage.Actions(f.e164 ?: f.raw, f.raw) },
                )
            }
        }
    }

    /** M11: hands the text to Parley's "Add several numbers" screen (in memory only) and closes the sheet. */
    private fun saveAll() {
        MessagingInbox.bulkText = sourceText
        startActivity(
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_BULK_ADD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    /**
     * M8: an empty number field. The clipboard is read only when the Paste chip is tapped; a national number is read
     * with the SIM's country unless another is chosen; the messengers appear once the number is complete.
     */
    @Composable
    private fun EnterNumber() {
        val defaultRegion = remember { PhoneEnv.countryIso(this) }
        var typed by rememberSaveable { mutableStateOf("") }
        var regionOverride by rememberSaveable { mutableStateOf<String?>(null) }
        var pickCountry by remember { mutableStateOf(false) }
        val region = regionOverride ?: defaultRegion
        val e164 = remember(typed, region) { NumberText.toE164(typed, region) }
        val ready = e164 != null && app.parley.common.MessengerLinks.unavailable(e164) == null
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

        fun paste() {
            val clip = runCatching {
                getSystemService(android.content.ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            }.getOrNull()?.take(MAX_TEXT)
            if (clip.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.num_nothing_to_paste), Toast.LENGTH_SHORT).show()
                return
            }
            val found = NumberText.find(clip, region)
            when {
                found.isEmpty() -> Toast.makeText(this, getString(R.string.num_no_number_copied), Toast.LENGTH_SHORT).show()
                found.size == 1 -> typed = found[0].raw
                else -> {
                    sourceText = clip
                    stage = Stage.Pick(found)
                }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.num_message_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            OutlinedTextField(
                typed, { typed = it.take(40) },
                label = { Text(stringResource(R.string.num_phone_number)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).focusRequester(focus),
            )
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.AssistChip(
                    onClick = { paste() },
                    label = { Text(stringResource(R.string.keypad_paste)) },
                    leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) },
                )
                androidx.compose.material3.AssistChip(
                    onClick = { pickCountry = true },
                    label = { Text(countryLabel(region)) },
                    leadingIcon = { Icon(Icons.Rounded.Public, null) },
                )
            }
            if (ready) {
                androidx.compose.runtime.key(e164) {
                    MessageOnContent(e164!!) { app -> afterLaunch(app != null) }
                }
            } else {
                Text(
                    stringResource(if (typed.isBlank()) R.string.num_type_or_paste else R.string.num_keep_typing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
        if (pickCountry) {
            CountryPickerDialog(selected = region, onDismiss = { pickCountry = false }) { code ->
                pickCountry = false
                regionOverride = code.takeUnless { it == defaultRegion }
            }
        }
    }

    @Composable
    private fun NumberActions(found: String, raw: String?) {
        val scope = rememberCoroutineScope()
        val defaultRegion = remember { PhoneEnv.countryIso(this) }
        // F19: a national number in the text is read with this phone's country unless the user picks another.
        var regionOverride by rememberSaveable(raw) { mutableStateOf<String?>(null) }
        var pickCountry by remember { mutableStateOf(false) }
        val region = regionOverride ?: defaultRegion
        val national = raw != null && !app.parley.common.PhoneNumbers.clean(raw).startsWith("+")
        val number = if (national && regionOverride != null) NumberText.toE164(raw, region) ?: found else found
        val e164 = remember(number, region) { NumberText.toE164(number, region) }
        var contactName by remember { mutableStateOf<String?>(null) }
        var askTemporary by remember { mutableStateOf(false) }
        val locked = remember { appLock }
        LaunchedEffect(number) {
            // With the app lock on, don't reveal who this is over another app.
            if (!locked) contactName = withContext(Dispatchers.IO) { container.contacts.lookup(number)?.name }
        }
        val where = remember(number) { NumberInfo.location(number, region) }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(contactName ?: Bidi.ltr(e164?.let(NumberText::formatInternational) ?: Format.number(number, region)), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            val sub = listOfNotNull(if (contactName != null) Bidi.ltr(e164?.let(NumberText::formatInternational) ?: number) else null, where).joinToString(stringResource(R.string.main_separator))
            if (sub.isNotEmpty()) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            if (national) {
                androidx.compose.material3.AssistChip(
                    onClick = { pickCountry = true },
                    label = { Text(stringResource(R.string.num_country, countryLabel(region))) },
                    leadingIcon = { Icon(Icons.Rounded.Public, null) },
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.main_call)) },
                leadingContent = { Icon(Icons.Rounded.Call, null) },
                modifier = Modifier.clickable { call(number, contactName) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.missed_message_on)) },
                supportingContent = { Text(stringResource(R.string.num_message_apps)) },
                leadingContent = { Icon(Icons.AutoMirrored.Rounded.Chat, null) },
                modifier = Modifier.clickable { stage = Stage.Message(number) },
            )
            if (contactName == null) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.keypad_add_to_contacts)) },
                    leadingContent = { Icon(Icons.Rounded.PersonAdd, null) },
                    modifier = Modifier.clickable {
                        startActivity(
                            Intent(this@NumberActionActivity, MainActivity::class.java)
                                .setAction(Intent.ACTION_INSERT_OR_EDIT)
                                .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                                .putExtra(ContactsContract.Intents.Insert.PHONE, e164 ?: number),
                        )
                        finish()
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.num_save_temporary)) },
                    supportingContent = { Text(pluralStringResource(R.plurals.num_private_deletes, TemporaryContact.DEFAULT_DAYS, TemporaryContact.DEFAULT_DAYS)) },
                    leadingContent = { Icon(Icons.Rounded.Timer, null) },
                    modifier = Modifier.clickable { askTemporary = true },
                )
            }
        }
        if (askTemporary) {
            TemporaryNameDialog(TemporaryContact.suggestedName(number, null, region), onDismiss = { askTemporary = false }) { name, visible ->
                askTemporary = false
                scope.launch { saveTemporary(e164 ?: number, name, visible) }
            }
        }
        if (pickCountry) {
            CountryPickerDialog(selected = region, onDismiss = { pickCountry = false }) { code ->
                pickCountry = false
                regionOverride = code.takeUnless { it == defaultRegion }
            }
        }
    }

    @Composable
    private fun OfferDialog(s: Stage.Offer) {
        val scope = rememberCoroutineScope()
        val region = remember { PhoneEnv.countryIso(this) }
        // F30: once, after the first WhatsApp chat opened from here.
        val notice = remember {
            if (s.via.startsWith("WhatsApp") && !container.messaging.whatsappSyncNoticeShown) {
                container.messaging.whatsappSyncNoticeShown = true
                getString(WhatsAppNotice.TEXT_RES)
            } else {
                null
            }
        }
        TemporaryNameDialog(
            TemporaryContact.suggestedName(s.number, s.via, region),
            title = stringResource(R.string.num_save_temporary_question),
            notice = notice,
            onDismiss = { finish() },
        ) { name, visible -> scope.launch { saveTemporary(s.number, name, visible) } }
    }

    private suspend fun saveTemporary(number: String, name: String, visible: Boolean) {
        val saved = withContext(Dispatchers.IO) { runCatching { TemporaryContact.save(container, number, name, private = !visible) }.getOrNull() }
        Toast.makeText(this, TemporaryContact.savedMessage(resources, saved), Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Same path as calls made in Parley ([app.parley.CallGate]): dial guard, allowance and confirm-before-call apply. */
    private fun call(number: String, name: String?) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            // Not the phone app: hand the number to Parley's keypad instead.
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).setClass(this, MainActivity::class.java))
            finish()
            return
        }
        lifecycleScope.launch {
            val sims = withContext(Dispatchers.IO) { container.sims.accounts() }
            callSims = sims
            val p = gate.check(number, name, sims.size)
            if (p != null) pendingCall = p else place(number, null, remember = false, confirmed = true, name = name)
        }
    }

    private fun place(number: String, simId: String?, remember: Boolean, confirmed: Boolean, name: String?) {
        pendingCall = null
        lifecycleScope.launch {
            when (val r = gate.place(number, simId, name, callSims, remember, confirmed)) {
                is app.parley.CallGate.Placed.Ask -> pendingCall = r.pending
                is app.parley.CallGate.Placed.Done -> {
                    (r.result as? PlaceResult.Failed)?.let { Toast.makeText(this@NumberActionActivity, it.reason, Toast.LENGTH_LONG).show() }
                    finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_MESSAGE_ON = "app.parley.action.MESSAGE_ON"
        const val EXTRA_NUMBER = "number"
        /** PhoneAccountHandle id of the call the number comes from (F19). */
        const val EXTRA_ACCOUNT_ID = "account_id"
        /** Selected or shared text beyond this is ignored (a phone number is never that far in). */
        private const val MAX_TEXT = 10_000
    }
}

/**
 * Name for a temporary contact, prefilled with "WhatsApp · +92 300 1234567". F5: saved privately unless
 * "Save visible to other apps" is ticked; [onSave] gets the name and that choice. [notice] is an extra line shown
 * first (F30).
 */
@Composable
fun TemporaryNameDialog(
    suggested: String,
    title: String? = null,
    notice: String? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, visible: Boolean) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(suggested) }
    var visible by rememberSaveable { mutableStateOf(false) }
    val days = TemporaryContact.DEFAULT_DAYS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: stringResource(R.string.num_save_temporary)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                Text(pluralStringResource(if (visible) R.plurals.num_temp_visible_body else R.plurals.num_temp_private_body, days, days))
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.edit_name)) }, singleLine = true)
                Row(
                    Modifier.fillMaxWidth().toggleable(visible, role = Role.Checkbox, onValueChange = { visible = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(visible, onCheckedChange = null)
                    Text(stringResource(R.string.num_save_visible), Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = { TextButton({ onSave(name, visible) }) { Text(stringResource(if (visible) R.string.main_save else R.string.sqr_save_privately)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.num_not_now)) } },
    )
}
