package app.parley.messaging

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ContactPage
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.parley.CallGate
import app.parley.IntentRoutes
import app.parley.MainActivity
import app.parley.MissedCallActionReceiver
import app.parley.PendingCall
import app.parley.R
import app.parley.blocking.DialText
import app.parley.calls.MissedCallNotifier
import app.parley.common.AppSettings
import app.parley.common.MessengerLinks
import app.parley.common.NumberText
import app.parley.common.PhoneIdentity
import app.parley.common.SimAccount
import app.parley.common.calls.EmergencyPolicy
import app.parley.common.catching
import app.parley.common.circle.Agenda
import app.parley.common.people.MapLinks
import app.parley.common.people.PasteParser
import app.parley.container
import app.parley.data.EmergencyNumbers
import app.parley.data.PhoneEnv
import app.parley.data.PlaceResult
import app.parley.security.AppLock
import app.parley.security.LockedActivity
import app.parley.security.VaultUnlockDeclined
import app.parley.telecom.CallManager
import app.parley.telecom.CallState
import app.parley.ui.Bidi
import app.parley.ui.Clipboard
import app.parley.ui.ConfirmDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import app.parley.ui.ParleyTheme
import app.parley.ui.common.CallQuestions
import app.parley.ui.common.Format
import app.parley.ui.common.ProvideAppKit
import app.parley.ui.common.rememberNumberLocation
import app.parley.ui.contact.PasteInbox
import app.parley.ui.showMessage
import app.parley.ui.temporary.TemporaryContactActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A small sheet over the current app for a phone number found in text: "Call / Message with Parley" in text
 * selection menus, and text shared to Parley. Also hosts the "Message on…" sheet for places outside Parley's own
 * screens (missed-call notification, in-call screen).
 *
 * Nothing starts without a tap: several numbers show a picker, and every action is a button. The text is only used
 * to find numbers and is never stored, unless the user adds it as something to talk about with someone. This
 * activity doesn't handle `tel:` links (the keypad does).
 */
class NumberActionActivity : LockedActivity() {
    // A sheet over another app: other apps' overlays can't cover its buttons.
    override val hidesOverlays = true

    private sealed interface Stage {
        data object NoNumber : Stage

        /** No number, but a map link (read on the phone): offer to save it as a contact's address. */
        data class Place(val place: MapLinks.Place) : Stage

        /** "Message a number" (tile, launcher shortcut): an empty field with Paste and the country. */
        data object Enter : Stage

        /** Shared text to talk about with someone: pick who. */
        data object AgendaPick : Stage
        data class Pick(val found: List<NumberText.Found>) : Stage
        /** [raw] is the number as written in the text, re-read when the user picks another country. */
        data class Actions(val number: String, val raw: String? = null) : Stage
        /** [accountId]: the SIM of the call the number comes from (missed-call notification), for its country. */
        data class Message(val number: String, val accountId: String? = null) : Stage
        data class Offer(val number: String, val via: String) : Stage
        /** An emergency number while Parley is locked: only its Call action, without asking for the unlock first. */
        data class Emergency(val number: String) : Stage

        /**
         * A call started outside Parley's screens: "Call back" from a missed-call notification ([missed]), or a widget,
         * shortcut or reminder with "Confirm before calling" on. No sheet, only the call's questions when the gate has
         * any (the unlock is asked only then), otherwise the call is placed at once. [name] is shown in the question.
         */
        data class CallBack(val number: String, val name: String?, val missed: Boolean) : Stage
    }

    private var stage by mutableStateOf<Stage>(Stage.NoNumber)

    /** The call's open questions (dial guard, allowance, confirm, SIM), as in Parley itself. */
    @get:VisibleForTesting
    internal var pendingCall by mutableStateOf<PendingCall?>(null)
        private set

    private var callSims by mutableStateOf<List<SimAccount>>(emptyList())
    private val gate by lazy { CallGate(container) }
    /** Names stay hidden while this is true; read from disk before anything is shown, then cleared by unlocking. */
    private var appLock = true
    private var settingsSnapshot: AppSettings? = null
    /** A chat with an unknown number was opened from here; offer a temporary contact when the user comes back. */
    private var awaitingReturn = false
    /** The selected, shared or pasted text, kept only while the sheet is open, for "Save all…". */
    private var sourceText: String? = null

    /** Shared text that is more than a number or a map link (a signature, a profile): offered as a new contact. */
    private var contactText by mutableStateOf<String?>(null)

    /** The selected or shared text, offered as something to talk about (kept only if the user adds it). */
    private var agendaText by mutableStateOf<String?>(null)
    private var leftForChat = false
    /** Nothing shows while this is true: the lock engaged again while the sheet was open. */
    private var hidden by mutableStateOf(false)
    private var authenticating = false
    private var stopped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        stage = initialStage(intent)
        // Secure until the settings say otherwise, so nothing is captured while they load.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            val settings = container.settings.current()
            appLock = settings.appLock
            hidden = true
            if (!settings.secureScreen) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setContent {
                ParleyTheme(settings.themeMode, settings.amoledBlack, settings.dynamicColor, settings.density) {
                    if (!hidden) ProvideAppKit { Sheet() }
                }
            }
            reveal(settings)
            settingsSnapshot = settings
            (stage as? Stage.CallBack)?.let(::callBack)
        }
    }

    /**
     * Any app can open this sheet. While Parley is locked it would tell whether a number is a (private) contact and
     * let "My details" be edited, so it asks for the unlock first, as Parley itself does, and again whenever the lock
     * engages while the sheet is open (it stays behind a chat opened from here). An emergency number is the
     * exception: it is offered alone, with nothing but its Call action, so no prompt stands before the call.
     */
    private fun reveal(settings: AppSettings) {
        AppLock.onStart(settings)
        if (!settings.appLock || !AppLock.locked.value) {
            appLock = false
            hidden = false
            return
        }
        appLock = true
        hidden = true
        val s = stage
        // Stays hidden: the unlock is asked only if the call has a question to show (see call()).
        if (s is Stage.CallBack) return
        if (s is Stage.Emergency) {
            hidden = false
            return
        }
        if (s is Stage.Actions) {
            lifecycleScope.launch {
                val emergency = withContext(Dispatchers.IO) {
                    listOfNotNull(s.raw, s.number).firstOrNull { EmergencyNumbers.isEmergency(this@NumberActionActivity, it) }
                }
                if (stage != s || !appLock) return@launch
                if (emergency != null) {
                    stage = Stage.Emergency(EmergencyPolicy.asciiDigits(emergency))
                    hidden = false
                } else {
                    askUnlock()
                }
            }
            return
        }
        askUnlock()
    }

    private fun askUnlock() {
        if (authenticating) return
        authenticating = true
        AppLock.authenticate(this) { ok ->
            authenticating = false
            // With a Parley PIN set only a PIN opens Parley, and this sheet has no PIN field: it closes.
            if (!ok || AppLock.locked.value) return@authenticate finish()
            appLock = false
            hidden = false
        }
    }

    override fun onStart() {
        super.onStart()
        // Back from a chat (or anywhere) after the lock engaged: hidden again until unlocked.
        if (stopped) settingsSnapshot?.let(::reveal)
        stopped = false
    }

    override fun onStop() {
        stopped = true
        AppLock.onStop(settingsSnapshot)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        awaitingReturn = false
        stage = initialStage(intent)
        // Still locked (e.g. showing an emergency number): the new text goes through the same gate.
        if (appLock) settingsSnapshot?.let(::reveal)
        if (settingsSnapshot != null) (stage as? Stage.CallBack)?.let(::callBack)
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
        contactText = null
        agendaText = null
        contactCheck?.cancel()
        val text = when (intent.action) {
            MessageNumber.ACTION -> return Stage.Enter
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ACTION_CALL_BACK, ACTION_CALL_CONFIRM -> callBackStage(intent)?.let { return it }
            ACTION_MESSAGE_ON -> intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() }?.let { return Stage.Message(it, intent.getStringExtra(EXTRA_ACCOUNT_ID)) }
            else -> null
        }.orEmpty().take(MAX_TEXT)
        return stageFor(text)
    }

    /** What text holds: one number, several, none but a map link (an unknown web link isn't one), or nothing. */
    private fun stageFor(text: String): Stage {
        val region = PhoneEnv.countryIso(this)
        val found = NumberText.find(text, region)
        if (found.size > 1) sourceText = text
        offerContactLater(text, region)
        // What the text says besides its number: something to talk about.
        agendaText = if (found.size > 1) null else Agenda.clean(found.singleOrNull()?.let { text.replace(it.raw, " ") } ?: text)
        return when (found.size) {
            0 -> MapLinks.parse(text)?.takeIf { it.hasCoordinates || it.needsNetwork || it.service != MapLinks.Service.OTHER }?.let { Stage.Place(it) }
                ?: Stage.NoNumber
            1 -> Stage.Actions(found[0].e164 ?: found[0].raw, found[0].raw)
            else -> Stage.Pick(found)
        }
    }

    /** The text being looked at for "Make a contact from this text"; a newer share replaces it. */
    private var contactCheck: Job? = null

    /**
     * Whether [text] is worth "Make a contact from this text" is the whole paste parser's work (number search per
     * line), so it runs off the main thread and the row appears when it's known.
     */
    private fun offerContactLater(text: String, region: String?) {
        contactCheck?.cancel()
        contactCheck = lifecycleScope.launch {
            val worth = withContext(Dispatchers.Default) { runCatching { PasteParser.worthOffering(text, region) }.getOrDefault(false) }
            if (worth) contactText = text
        }
    }

    /** Only through the unexported alias: this activity is exported, and a call back may place the call at once. */
    private fun callBackStage(intent: Intent): Stage? =
        intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() && intent.component?.className == CALL_BACK_ALIAS }
            ?.let { Stage.CallBack(it, intent.getStringExtra(EXTRA_NAME)?.takeIf(String::isNotBlank), missed = intent.action == ACTION_CALL_BACK) }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    @Suppress("CyclomaticComplexMethod") // One branch per stage.
    private fun Sheet() {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        pendingCall?.let { p ->
            CallQuestions(
                p, callSims, PhoneEnv.countryIso(this),
                onUpdate = { next -> pendingCall = next; if (next == null) finish() },
                onPlace = { number, simId, remember, confirmed -> place(number, simId, remember, confirmed, p.name) },
            )
            return
        }
        when (val s = stage) {
            is Stage.Offer -> OfferDialog(s)
            is Stage.CallBack -> Unit
            else -> ParleySheet(onDismissRequest = { finish() }, sheetState = sheetState) {
                when (s) {
                    Stage.NoNumber -> Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.num_none_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.num_none_body), style = MaterialTheme.typography.bodyMedium)
                        if (agendaText != null) {
                            ParleyListItem(
                                headlineContent = { Text(stringResource(R.string.agenda_share_row)) },
                                supportingContent = { Text(stringResource(R.string.agenda_share_row_body)) },
                                leadingContent = { Icon(Icons.Rounded.Checklist, null) },
                                modifier = Modifier.clickable { stage = Stage.AgendaPick },
                            )
                        }
                        MakeContactRow()
                        TextButton({ finish() }) { Text(stringResource(R.string.main_close)) }
                    }
                    is Stage.Place -> PlaceActions(s.place)
                    Stage.Enter -> EnterNumber()
                    Stage.AgendaPick -> AgendaPick(container, agendaText.orEmpty(), ::finish)
                    is Stage.Pick -> PickNumber(s.found)
                    is Stage.Actions -> NumberActions(s.number, s.raw)
                    is Stage.Message -> ReachSheetContent(ReachTarget.Number(s.number, s.accountId), onCall = callAction()) { app -> afterLaunch(app != null) }
                    is Stage.Offer, is Stage.CallBack -> Unit
                    is Stage.Emergency -> EmergencyCall(s.number)
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
                // Several numbers in the text can be saved together, after a review.
                ParleyListItem(
                    headlineContent = { Text(pluralStringResource(R.plurals.num_save_all, found.size, found.size)) },
                    supportingContent = { Text(stringResource(R.string.num_save_all_body)) },
                    leadingContent = { Icon(Icons.Rounded.GroupAdd, null) },
                    modifier = Modifier.clickable { saveAll() },
                )
            }
            MakeContactRow()
            found.forEach { f ->
                ParleyListItem(
                    headlineContent = { Text(Bidi.ltr(f.e164?.let(NumberText::formatInternational) ?: f.raw)) },
                    supportingContent = { Text(stringResource(R.string.num_in_text, f.raw)) },
                    modifier = Modifier.clickable { stage = Stage.Actions(f.e164 ?: f.raw, f.raw) },
                )
            }
        }
    }

    /** A shared map link: what Parley read from it, and "Add to a contact…" (a new contact or an existing one). */
    @Composable
    private fun PlaceActions(p: MapLinks.Place) {
        val lat = p.lat
        val lon = p.lon
        val what = listOfNotNull(p.name, if (lat != null && lon != null) MapLinks.formatPair(lat, lon) else null).joinToString(" · ").ifEmpty { p.link }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.map_link_place_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(what, style = MaterialTheme.typography.bodyLarge)
            if (p.needsNetwork) {
                Text(stringResource(R.string.map_link_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.map_link_share_body), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ finish() }) { Text(stringResource(R.string.main_close)) }
                TextButton({ addPlace(p) }) { Text(stringResource(R.string.map_link_add_to_contact)) }
            }
        }
    }

    /**
     * Opens Parley's "Save contact details" choice (new contact, or add to an existing one) with the place as a home
     * address and its map link as the address's "Map (Home)" website row, through the standard insert-or-edit extras.
     */
    private fun addPlace(p: MapLinks.Place) {
        val lat = p.lat
        val lon = p.lon
        val address = p.name ?: if (lat != null && lon != null) MapLinks.formatPair(lat, lon) else ""
        val site = ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.Website.URL, MapLinks.storedLink(p))
            put(ContactsContract.CommonDataKinds.Website.TYPE, ContactsContract.CommonDataKinds.Website.TYPE_CUSTOM)
            put(ContactsContract.CommonDataKinds.Website.LABEL, MapLinks.label("Home"))
        }
        startActivity(
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_INSERT_OR_EDIT)
                .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                .putExtra(ContactsContract.Intents.Insert.POSTAL, address)
                .putExtra(ContactsContract.Intents.Insert.POSTAL_TYPE, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME)
                .putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, arrayListOf(site))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    /** "Make a contact from this text": the details read from the shared text, ticked in a new contact's editor. */
    @Composable
    private fun MakeContactRow() {
        val text = contactText ?: return
        ParleyListItem(
            headlineContent = { Text(stringResource(R.string.paste_make_contact)) },
            supportingContent = { Text(stringResource(R.string.paste_make_contact_body)) },
            leadingContent = { Icon(Icons.Rounded.ContactPage, null) },
            modifier = Modifier.clickable { makeContact(text) },
        )
    }

    /** Hands the text to a new contact's editor (in memory only, like "Save all…") and closes the sheet. */
    private fun makeContact(text: String) {
        val id = PasteInbox.put(text)
        startActivity(
            IntentRoutes.own(this).setAction(IntentRoutes.ACTION_PASTE_CONTACT).putExtra(IntentRoutes.EXTRA_PASTE_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    /** Hands the text to Parley's "Add several numbers" screen (in memory only) and closes the sheet. */
    private fun saveAll() {
        MessagingInbox.bulkText = sourceText
        startActivity(
            IntentRoutes.own(this).setAction(MainActivity.ACTION_BULK_ADD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    /**
     * An empty number field. The clipboard is read only when the Paste chip is tapped; a national number is read
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
        val ready = e164 != null && MessengerLinks.unavailable(e164) == null
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

        fun paste() {
            val clip = Clipboard.readText(this, MAX_TEXT)
            if (clip.isNullOrBlank()) {
                showMessage(this, getString(R.string.num_nothing_to_paste))
                return
            }
            val found = NumberText.find(clip, region)
            when {
                found.isEmpty() -> showMessage(this, getString(R.string.num_no_number_copied))
                found.size == 1 -> typed = found[0].raw
                else -> {
                    sourceText = clip
                    stage = Stage.Pick(found)
                }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.shortcut_message_number_short), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            OutlinedTextField(
                typed, { typed = it.take(40) },
                label = { Text(stringResource(R.string.blk_phone_number)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).focusRequester(focus),
            )
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { paste() },
                    label = { Text(stringResource(R.string.keypad_paste)) },
                    leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) },
                )
                AssistChip(
                    onClick = { pickCountry = true },
                    label = { Text(countryLabel(region)) },
                    leadingIcon = { Icon(Icons.Rounded.Public, null) },
                )
            }
            if (ready) {
                key(e164) {
                    ReachSheetContent(ReachTarget.Number(e164!!), onCall = callAction()) { app -> afterLaunch(app != null) }
                }
            } else {
                // A number messengers can't open (short or service numbers) can still be called.
                if (typed.count { it.isDigit() } >= 3) callAction()?.let { call -> CallFirstButton(typed) { call(typed) } }
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

    /** Only the number and its Call action: nothing about who it is, nothing else to do while Parley is locked. */
    @Composable
    private fun EmergencyCall(number: String) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(Bidi.ltr(number), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            callAction()?.let { call -> CallFirstButton(number) { call(number) } }
        }
    }

    @Composable
    private fun NumberActions(found: String, raw: String?) {
        val scope = rememberCoroutineScope()
        val defaultRegion = remember { PhoneEnv.countryIso(this) }
        // A national number in the text is read with this phone's country unless the user picks another.
        var regionOverride by rememberSaveable(raw) { mutableStateOf<String?>(null) }
        var pickCountry by remember { mutableStateOf(false) }
        val region = regionOverride ?: defaultRegion
        val national = raw != null && !PhoneIdentity.clean(raw).startsWith("+")
        val number = if (national && regionOverride != null) NumberText.toE164(raw, region) ?: found else found
        val e164 = remember(number, region) { NumberText.toE164(number, region) }
        var contactName by remember { mutableStateOf<String?>(null) }
        // Already a contact or a private contact: nothing to save (looked up even while locked, never shown then).
        var known by remember(number) { mutableStateOf(false) }
        var askTemporary by remember { mutableStateOf(false) }
        val locked = remember { appLock }
        LaunchedEffect(number) {
            val (name, isKnown) = withContext(Dispatchers.IO) {
                // Who owns it, as everywhere ([app.parley.data.people.NumberOwners]); a private contact's name never shows here.
                val found = catching { container.numberOwners.find(number, null) }.getOrNull()
                (found?.contact?.name ?: found?.archived?.name) to (found?.saved == true)
            }
            known = isKnown
            // With the app lock on, don't reveal who this is over another app.
            if (!locked) contactName = name
        }
        val where = rememberNumberLocation(number, region)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(contactName ?: Bidi.ltr(e164?.let(NumberText::formatInternational) ?: Format.number(number, region)), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            val sub = listOfNotNull(if (contactName != null) Bidi.ltr(e164?.let(NumberText::formatInternational) ?: number) else null, where).joinToString(stringResource(R.string.main_separator))
            if (sub.isNotEmpty()) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            if (national) {
                AssistChip(
                    onClick = { pickCountry = true },
                    label = { Text(stringResource(R.string.num_country, countryLabel(region))) },
                    leadingIcon = { Icon(Icons.Rounded.Public, null) },
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            // Call is the primary action, above the messengers.
            if (callAction() != null) CallFirstButton(number) { call(number, contactName) }
            ParleyListItem(
                headlineContent = { Text(stringResource(R.string.contact_page_sec_messengers)) },
                supportingContent = { Text(stringResource(R.string.reach_apps_line)) },
                leadingContent = { Icon(Icons.AutoMirrored.Rounded.Chat, null) },
                modifier = Modifier.clickable { stage = Stage.Message(number) },
            )
            if (contactName == null && !known) {
                ParleyListItem(
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
                ParleyListItem(
                    headlineContent = { Text(stringResource(R.string.num_save_temporary)) },
                    supportingContent = { Text(pluralStringResource(R.plurals.num_private_deletes, TemporaryContact.DEFAULT_DAYS, TemporaryContact.DEFAULT_DAYS)) },
                    leadingContent = { Icon(Icons.Rounded.Timer, null) },
                    modifier = Modifier.clickable { askTemporary = true },
                )
            }
            agendaText?.let { AgendaNumberRow(container, it, number, contactName, ::finish) }
            MakeContactRow()
        }
        if (askTemporary) {
            TemporaryNameDialog(TemporaryContact.suggestedName(number, null, region), onDismiss = { askTemporary = false }) { name, visible ->
                scope.launch { if (saveTemporary(e164 ?: number, name, visible)) askTemporary = false }
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
        // Once, after the first WhatsApp chat opened from here.
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

    /** False when the private contacts' unlock was cancelled: nothing saved, and the question stays. */
    private suspend fun saveTemporary(number: String, name: String, visible: Boolean): Boolean {
        val saved = try {
            TemporaryContactActions.saveUnlocking(this) {
                withContext(Dispatchers.IO) { TemporaryContact.save(container, number, name, private = !visible) }
            }
        } catch (_: VaultUnlockDeclined) {
            return false
        }
        showMessage(this, TemporaryContact.savedMessage(resources, saved))
        finish()
        return true
    }

    /**
     * The sheet's Call action, or null during a call (the in-call screen's caller card opens this sheet too, and a
     * second call to the same person from there would only put the first on hold).
     */
    private fun callAction(): ((String) -> Unit)? =
        if (CallManager.state.value.any { it.state != CallState.DISCONNECTED && it.state != CallState.DISCONNECTING }) {
            null
        } else {
            { n -> call(n, null) }
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
            if (p != null) {
                pendingCall = p
                // A call back while Parley is locked: its questions show only after the unlock.
                if (hidden && stage is Stage.CallBack) askUnlock()
            } else {
                place(number, null, remember = false, confirmed = true, name = name)
            }
        }
    }

    /**
     * The call takes the usual path. Only the missed-call notification's "Call back" marks the missed calls seen, and
     * only once the call is placed ([place]): a widget or shortcut call, or a cancelled question, leaves them as they were.
     */
    private fun callBack(s: Stage.CallBack) = call(s.number, s.name)

    /** After a call was placed: a call back from the missed-call notification means the missed calls were seen. */
    private fun placed() {
        if ((stage as? Stage.CallBack)?.missed != true) return
        val app = applicationContext
        container.scope.launch {
            MissedCallNotifier.cancelAll(app)
            MissedCallActionReceiver.seen(app)
        }
    }

    private fun place(number: String, simId: String?, remember: Boolean, confirmed: Boolean, name: String?) {
        pendingCall = null
        lifecycleScope.launch {
            when (val r = gate.place(number, simId, name, callSims, remember, confirmed)) {
                is CallGate.Placed.Ask -> pendingCall = r.pending
                is CallGate.Placed.Done -> {
                    val failed = r.result as? PlaceResult.Failed
                    if (failed != null) {
                        showMessage(this@NumberActionActivity, DialText.placeFailure(this@NumberActionActivity, failed.reason), long = true)
                    } else {
                        placed()
                    }
                    finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_MESSAGE_ON = "app.parley.action.MESSAGE_ON"
        const val ACTION_CALL_BACK = "app.parley.action.CALL_BACK"

        /** A call from a widget, shortcut or reminder that asks first ("Confirm before calling"). */
        const val ACTION_CALL_CONFIRM = "app.parley.action.CALL_CONFIRM"
        const val EXTRA_NUMBER = "number"

        /** The contact's name, shown in the call's question. */
        const val EXTRA_NAME = "name"

        /** PhoneAccountHandle id of the call the number comes from. */
        const val EXTRA_ACCOUNT_ID = "account_id"
        /** Selected or shared text beyond this is ignored (a phone number is never that far in). */
        private const val MAX_TEXT = 10_000

        /** The unexported activity-alias the missed-call notification starts (see the manifest). */
        private const val CALL_BACK_ALIAS = "app.parley.messaging.MissedCallBack"

        /** "Call back" [number] through the call gate (the missed-call notification's action). */
        fun callBackIntent(context: Context, number: String): Intent = Intent()
            .setClassName(context, CALL_BACK_ALIAS)
            .setAction(ACTION_CALL_BACK)
            .putExtra(EXTRA_NUMBER, number)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * Calls [number] through the call gate with its questions ("Confirm before calling" with [name]), for widgets,
         * shortcuts and reminders. Unlike [callBackIntent] it never touches the missed calls.
         */
        fun confirmCallIntent(context: Context, number: String, name: String?): Intent = Intent()
            .setClassName(context, CALL_BACK_ALIAS)
            .setAction(ACTION_CALL_CONFIRM)
            .putExtra(EXTRA_NUMBER, number)
            .putExtra(EXTRA_NAME, name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Name for a temporary contact, prefilled with "WhatsApp · +92 300 1234567". Saved privately unless
 * "Save visible to other apps" is ticked; [onSave] gets the name and that choice. [notice] is an extra line shown
 * first.
 */
@Composable
fun TemporaryNameDialog(
    suggested: String,
    title: String? = null,
    notice: String? = null,
    initialVisible: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (name: String, visible: Boolean) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(suggested) }
    var visible by rememberSaveable { mutableStateOf(initialVisible) }
    val days = TemporaryContact.DEFAULT_DAYS
    ConfirmDialog(
        title = title ?: stringResource(R.string.num_save_temporary),
        text = null,
        confirmLabel = stringResource(if (visible) R.string.pin_save else R.string.temp_save_privately),
        onConfirm = { onSave(name, visible) },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.circle_not_now),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                Text(pluralStringResource(if (visible) R.plurals.num_temp_visible_body else R.plurals.num_temp_private_body, days, days))
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.agenda_share_search)) }, singleLine = true)
                Row(
                    Modifier.fillMaxWidth().toggleable(visible, role = Role.Checkbox, onValueChange = { visible = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(visible, onCheckedChange = null)
                    Text(stringResource(R.string.temp_visible), Modifier.padding(start = 8.dp))
                }
            }
        },
    )
}
