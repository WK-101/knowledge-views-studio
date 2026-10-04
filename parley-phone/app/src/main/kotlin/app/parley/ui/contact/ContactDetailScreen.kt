package app.parley.ui.contact

import app.parley.common.calls.CallReason
import app.parley.data.primary
import app.parley.jobs.UserErrorText
import app.parley.ui.Clipboard
import app.parley.ui.ParleyListItem
import app.parley.ui.menus.CallReasonFlow
import app.parley.ui.menus.MenuShortcutsBlock
import app.parley.ui.menus.ReasonTarget
import app.parley.ui.people.cards.CardUpdateBanner
import app.parley.ui.Destination
import android.provider.ContactsContract
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.ui.text.input.TextFieldValue
import androidx.activity.ComponentActivity
import app.parley.NavEvent
import app.parley.common.ContactSummary
import app.parley.common.EventDate
import app.parley.common.MessengerApp
import app.parley.common.PhoneIdentity
import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import app.parley.common.StartTab
import app.parley.common.circle.YearlyEvents
import app.parley.common.people.ContactPage
import app.parley.common.people.ContactSection
import app.parley.common.people.SocialProfiles
import app.parley.common.ReachGroups
import app.parley.common.people.ContactGlance
import app.parley.common.people.GlanceFact
import app.parley.common.people.PageBlock
import app.parley.common.people.SectionFamily
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.AlarmAdd
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.photo.OriginalPhoto
import app.parley.common.people.HandleLink
import app.parley.common.people.LifeEvents
import app.parley.common.people.MessageRoute
import app.parley.common.people.MessageRoutes
import androidx.compose.foundation.Image
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.parley.common.people.MessengerPrefs
import app.parley.common.people.RelationTypes
import app.parley.common.people.RelationshipStatus
import app.parley.data.people.RelationMirrors
import app.parley.common.ux.Tips
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.PhoneEnv
import app.parley.data.circle.Interaction
import app.parley.messaging.ReachSheet
import app.parley.messaging.ReachTarget
import app.parley.shortcuts.Shortcuts
import app.parley.ui.Avatar
import app.parley.ui.Bidi
import app.parley.ui.OnGroupSurface
import app.parley.ui.Routes
import app.parley.ui.blocking.ContactPrefixAllowMenuItem
import app.parley.ui.blocking.askToBlock
import app.parley.ui.calls.DefaultAppNote
import app.parley.ui.calls.RemindToCallSheet
import app.parley.common.ux.DefaultAppFeature
import app.parley.ui.blocking.rememberBlocked
import app.parley.ui.blocking.unblockWithUndo
import app.parley.ui.calltime.ContactCallTimeRows
import app.parley.ui.circle.ContactTimeline
import app.parley.ui.circle.LogInteractionDialog
import app.parley.ui.circle.PreCallPeekSheet
import app.parley.ui.circle.PromiseNoteField
import app.parley.ui.circle.PromisesCard
import app.parley.ui.circle.RhythmDialog
import app.parley.ui.circle.StayInTouchCard
import app.parley.ui.circle.goodTimeText
import app.parley.ui.circle.hasPeek
import app.parley.ui.circle.timelineEntries
import app.parley.ui.common.CoachMark
import app.parley.ui.history.CallInsightsSection
import app.parley.ui.people.AccountChips
import app.parley.ui.people.CallBackgroundInfoRow
import app.parley.ui.people.CallPhotoRow
import app.parley.ui.people.CopyToSimDialog
import app.parley.ui.people.ProvenanceRow
import app.parley.ui.people.RelationText
import app.parley.ui.people.describeLifeEvent
import app.parley.ui.people.eventLabel
import app.parley.ui.screenViewModel
import app.parley.ui.blended
import app.parley.ui.common.Format
import app.parley.ui.common.Intents
import app.parley.ui.shared
import app.parley.security.launchVault
import app.parley.ui.vault.ExpiryDialog
import app.parley.common.people.ContactCapabilities
import app.parley.common.people.ContactCapability
import app.parley.common.people.VariantChip
import app.parley.data.AccountRef
import app.parley.security.AppLock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold
import app.parley.ui.BackButton
import app.parley.ui.ParleyDialog
import app.parley.ui.ConfirmDialog
import app.parley.ui.Spacing
import app.parley.ui.ParleyMotion
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.LockOpen

/**
 * A contact's page. U1: the photo and name dock into the top bar as you scroll ("last talked" shows there once
 * collapsed); U3: labelled Call / Message / Video / Email tiles; M6/M7: "Message or call on…" with a remembered
 * choice per person; I1 handles, I3 default number or email, I4 other fields, I5 relation types.
 *
 * Laid out to be calm and compact (docs/CONTACT_PAGE_DESIGN.md): a modest photo with an "at a glance" line under
 * the name (last talked, the next date, open promises); one "Contact info" group where each number says which apps
 * reach it; one "About" group for dates, websites, relations and notes; the timeline's latest few entries; and
 * everything that is a setting rather than a fact in one folded "Settings for this contact" group at the bottom.
 * Every group folds, in the order and start state chosen in Settings (neighbouring sections of one family share
 * a group); a compact bar with the quick actions (and jump chips on long pages) stays under the top bar once
 * scrolled. "Log interaction" is the FAB for Circle contacts and a ⋮ item for everyone else.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactDetailScreen(vm: AppViewModel, contactId: Long, back: () -> Unit, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val page: ContactDetailViewModel = screenViewModel()
    LaunchedEffect(contactId) { page.start(contactId) }
    LaunchedEffect(page) { page.events.collect { vm.toast(it) } }
    val ui by page.state.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val simPrefs = ui.simPrefs
    val details = ui.details
    val loaded = ui.loaded
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // The sealed copy of a private contact couldn't be kept: ask before deleting it without one.
    var confirmDeleteNoCopy by remember { mutableStateOf(false) }
    // The viewers come back after a rotation, so a "Save to" they opened still writes its file.
    var showQr by rememberSaveable { mutableStateOf(false) }
    var simFor by remember { mutableStateOf<String?>(null) }
    var showPhoto by rememberSaveable { mutableStateOf(false) }
    var askExpiry by remember { mutableStateOf(false) }
    var relationChoice by remember { mutableStateOf<List<ContactSummary>?>(null) }
    var pinDialog by remember { mutableStateOf(false) }
    var reachOut by remember { mutableStateOf(false) }
    var secureQr by rememberSaveable { mutableStateOf(false) }
    var copyToSim by remember { mutableStateOf(false) }
    var messageSheet by remember { mutableStateOf<String?>(null) }
    var webLink by remember { mutableStateOf<HandleLink?>(null) }
    var editNote by remember { mutableStateOf(false) }
    // The page's data comes from its view model, read for this person only (ContactDetailViewModel).
    val messengers = ui.messengers
    val meta = ui.meta
    val otherFields = ui.otherFields
    val interactions = ui.interactions
    var logDialog by remember { mutableStateOf(false) }
    var remindToCall by remember { mutableStateOf(false) }
    // The pre-call peek (the number about to be called).
    val circleCfg by page.circleConfig.collectAsStateWithLifecycle()
    val relationsFromOthers by page.relationsFromOthers.collectAsStateWithLifecycle()
    val parleyRelations by page.parleyRelations.collectAsStateWithLifecycle()

    /** Opens the contact relation [name] names: by the remembered link first, then by name; several namesakes: ask. */
    fun openRelation(name: String) = page.openRelation(name) { target ->
        when (target) {
            is RelationTarget.Contact -> open(Routes.contact(target.id))
            is RelationTarget.Choose -> relationChoice = target.people
            is RelationTarget.None -> vm.toast(resources.getString(R.string.detail_no_contact_named, target.name))
        }
    }
    var peekNumber by remember { mutableStateOf<String?>(null) }
    // I12: "Call with a reason…" from a long-press on Call.
    var reasonFor by remember { mutableStateOf<ReasonTarget?>(null) }
    var editEntry by remember { mutableStateOf<Interaction?>(null) }
    val prefs = ui.prefs
    fun savePrefs(p: MessengerPrefs) = page.setMessengerPrefs(p)
    val temp = ui.temporary
    // One page for every contact: a private one differs only by what only the address book can do (ContactCapabilities).
    val isPrivate = ui.isPrivate
    val caps = ContactCapabilities.of(ui.storage)
    fun can(c: ContactCapability) = c in caps
    val locked = ui.access == PrivateAccess.LOCKED || ui.access == PrivateAccess.UNAVAILABLE
    var confirmPrivate by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var confirmPrivateQr by remember { mutableStateOf(false) }

    /** The vault's unlock, in this page; the details load again once it succeeds. */
    fun unlock() {
        (context as? ComponentActivity)?.let { AppLock.authenticateForVault(it) { ok -> if (ok) page.reload() } }
    }

    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            page.setRingtone(uri)
        }
    }

    val d = details
    val history = ui.history
    // Every note about this person (call notes, interaction notes, pinned note), and its open promises.
    val memory = ui.memory
    // A good time to call, from the calls with them and their local time.
    val goodTime = remember(history, d?.phones) {
        val p = d?.phones?.primary()?.value
        goodTimeText(resources, history, p, PhoneEnv.countryIso(context))
    }
    /** Calls [number], through the pre-call peek when there's something to remember and it's on. */
    fun callPeek(number: String, name: String) {
        if (circleCfg.preCallPeek && hasPeek(memory, goodTime)) peekNumber = number else vm.requestCall(number, name)
    }
    val talked = history.firstOrNull { it.durationSec > 0 }
    val lastTalked = if (talked != null) stringResource(R.string.detail_last_talked, DateUtils.getRelativeTimeSpanString(talked.date, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS)) else stringResource(R.string.recents_empty)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // The header photo: a real photo gets the most room, a monogram less, and a landscape phone keeps it small
    // enough to leave the actions in view. Photo, name, the at-a-glance line and the tiles fit in about a third
    // of an upright phone's screen.
    val heroSize = heroPhotoSize(details?.photoUri != null)
    // The photo as picked (whole, in its own shape) when Parley kept it; a tall one makes the header taller.
    val original = rememberOriginalPhoto(vm, details?.lookupKey, details?.photoUri)
    val heroHeight = heroSize * (original?.let { OriginalPhoto.hero(it.width, it.height).height } ?: 1f)
    // The header has scrolled away once the name is under the top bar.
    val collapseAt = with(density) { (heroHeight + 56.dp).toPx() }
    val collapsed by remember(collapseAt) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > collapseAt } }
    val headerFraction by remember(collapseAt) {
        derivedStateOf { if (listState.firstVisibleItemIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / collapseAt).coerceIn(0f, 1f) }
    }
    // The page's sections (order, start modes, remembered folds); a fold shows at once, then is stored.
    val peopleSettings by vm.people.settings.collectAsStateWithLifecycle()
    var layout by remember { mutableStateOf(peopleSettings.contactPage) }
    LaunchedEffect(peopleSettings.contactPage) { layout = peopleSettings.contactPage }
    fun fold(b: PageBlock, folded: Boolean) {
        layout = layout.withFold(b, folded)
        vm.people.update { it.copy(contactPage = it.contactPage.withFold(b, folded)) }
    }
    // The compact action bar is pinned once the big tiles have scrolled under the top bar.
    var headerHeight by remember { mutableIntStateOf(0) }
    var pinnedHeight by remember { mutableIntStateOf(0) }
    val pinAt = with(density) { 48.dp.toPx() }
    val pinned by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || (headerHeight > 0 && listState.firstVisibleItemScrollOffset > headerHeight - pinAt) }
    }
    // The bar takes the scrolled-content tint once content passes under it.
    val barColor by animateColorAsState(
        if (listState.canScrollBackward) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface, label = "bar",
    )

    fun reach(dd: ContactDetails) = Reach(
        name = dd.given.ifBlank { dd.displayName },
        numbers = dd.phones.map { it.value to Format.phoneType(resources, it.type, it.label) },
        defaultNumber = dd.phones.primary()?.value,
        messengers = messengers,
        prefs = prefs,
        // A private contact: nothing about reaching them is written outside Parley's encrypted storage.
        isPrivate = isPrivate,
        lookupKey = dd.lookupKey.takeUnless { isPrivate },
        contactId = contactId.takeUnless { isPrivate },
    )
    fun message(dd: ContactDetails, number: String? = null) {
        val r = reach(dd).let { if (number != null) it.copy(defaultNumber = number, prefs = it.prefs.copy(number = null)) else it }
        when (val route = ContactMessaging.route(context, r)) {
            MessageRoute.Ask -> messageSheet = number ?: r.defaultNumber ?: ""
            else -> ContactMessaging.open(context, route, r)?.let { vm.toast(it) }
        }
    }

    // A number's own Message button: a message to that number (the usual chat app, or a text), never the sheet; the
    // row's "Message or call on…" button is the one that opens it.
    fun messageNumber(dd: ContactDetails, number: String) {
        val r = reach(dd).copy(defaultNumber = number)
        val route = MessageRoutes.forNumber(r.prefs, r.linked, ContactMessaging.installed(context), number)
        ContactMessaging.open(context, route, r)?.let { vm.toast(it) }
    }

    val numbersBlocked = rememberBlocked(vm, remember(d?.phones) { d?.phones?.map { it.value }.orEmpty() })
    ParleyScaffold(
        topBar = {
            ParleyTopBar(
                title = {
                    AnimatedVisibility(collapsed && d != null, enter = fadeIn(), exit = fadeOut()) {
                        if (d != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(d.displayName, d.photoUri, 36.dp, isCompany = d.composedName.isBlank() && d.company.isNotBlank())
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(d.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(lastTalked, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = barColor, scrolledContainerColor = barColor),
                navigationIcon = { BackButton(back) },
                actions = {
                    if (d != null) {
                        IconButton({ page.setStarred(!d.starred) }) {
                            Icon(if (d.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, stringResource(if (d.starred) R.string.sel_unstar else R.string.sel_star))
                        }
                        IconButton({ open(if (isPrivate) Routes.edit(vault = -contactId) else Routes.edit(id = contactId)) }) {
                            Icon(Icons.Rounded.Edit, stringResource(R.string.main_edit))
                        }
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.main_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            // "Log interaction" is the FAB for Circle contacts; for everyone else it's here.
                            if (meta?.reachOutDays == null) DropdownMenuItem({ Text(stringResource(R.string.circle_log_interaction)) }, leadingIcon = { Icon(Icons.Rounded.Handshake, null) }, onClick = { menu = false; logDialog = true })
                            // To call by hand (the same fixed times as Remind me after a call).
                            if (d.phones.isNotEmpty()) {
                                DropdownMenuItem(
                                    { Text(stringResource(R.string.to_call_remind_me_to_call)) }, leadingIcon = { Icon(Icons.Rounded.AlarmAdd, null) },
                                    onClick = { menu = false; remindToCall = true },
                                )
                            }
                            if (can(ContactCapability.SHARE_VCARD_FILE)) {
                                DropdownMenuItem({ Text(stringResource(R.string.detail_share_file)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = {
                                    menu = false; Intents.shareVcard(context, page.vcardUri(d.lookupKey), d.displayName)
                                })
                            }
                            // A private contact's plain code is shown after saying what the scanner gets.
                            DropdownMenuItem({ Text(stringResource(R.string.detail_show_qr)) }, leadingIcon = { Icon(Icons.Rounded.QrCode2, null) }, onClick = {
                                menu = false; if (isPrivate) confirmPrivateQr = true else showQr = true
                            })
                            DropdownMenuItem({ Text(stringResource(R.string.detail_share_private)) }, leadingIcon = { Icon(Icons.Rounded.Lock, null) }, onClick = { menu = false; secureQr = true })
                            if (can(ContactCapability.VERSION_HISTORY)) {
                                DropdownMenuItem({ Text(stringResource(R.string.detail_versions)) }, leadingIcon = { Icon(Icons.Rounded.History, null) }, onClick = { menu = false; open(Routes.versions(contactId)) })
                            }
                            if (can(ContactCapability.HOME_SCREEN_SHORTCUT)) {
                                DropdownMenuItem({ Text(stringResource(R.string.detail_add_home)) }, leadingIcon = { Icon(Icons.Rounded.AddToHomeScreen, null) }, onClick = { menu = false; pinDialog = true })
                            }
                            if (d.phones.isNotEmpty() && can(ContactCapability.COPY_TO_SIM)) {
                                DropdownMenuItem(
                                    { Text(stringResource(R.string.detail_copy_sim)) }, leadingIcon = { Icon(Icons.Rounded.SimCard, null) },
                                    onClick = { menu = false; copyToSim = true },
                                )
                            }
                            if (can(ContactCapability.RINGTONE)) DropdownMenuItem({ Text(stringResource(R.string.detail_set_ringtone)) }, leadingIcon = { Icon(Icons.Rounded.MusicNote, null) }, onClick = {
                                menu = false
                                ringtonePicker.launch(
                                    Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, d.customRingtone?.let(Uri::parse)),
                                )
                            })
                            if (d.phones.isNotEmpty()) {
                                // The one Block (a question, then Undo); Unblock once any of the numbers is blocked.
                                if (numbersBlocked) {
                                    DropdownMenuItem({ Text(stringResource(R.string.detail_unblock_numbers)) }, leadingIcon = { Icon(Icons.Rounded.RemoveModerator, null) }, onClick = {
                                        menu = false; unblockWithUndo(vm, d.phones.map { it.value }, d.displayName)
                                    })
                                } else {
                                    DropdownMenuItem({ Text(stringResource(R.string.detail_block_numbers)) }, leadingIcon = { Icon(Icons.Rounded.Block, null) }, onClick = {
                                        menu = false; askToBlock(d.phones.map { it.value }, d.displayName)
                                    })
                                }
                            }
                            ContactPrefixAllowMenuItem(d.composedName.ifBlank { null }, d.phones.map { it.value }) { menu = false }
                            if (d.rawContacts.size > 1) {
                                DropdownMenuItem({ Text(stringResource(R.string.detail_separate)) }, leadingIcon = { Icon(Icons.Rounded.LinkOff, null) }, onClick = {
                                    menu = false; page.separate(back)
                                })
                            }
                            // Make private ⇄ Make visible: the same contact, kept somewhere else (asks first).
                            DropdownMenuItem(
                                { Text(stringResource(if (isPrivate) R.string.contact_make_visible else R.string.detail_move_vault)) },
                                leadingIcon = { Icon(if (isPrivate) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null) },
                                onClick = { menu = false; if (isPrivate) confirmVisible = true else confirmPrivate = true },
                            )
                            DropdownMenuItem({ Text(stringResource(if (temp != null) R.string.detail_change_expiry else R.string.detail_delete_after)) }, leadingIcon = { Icon(Icons.Rounded.Timer, null) }, onClick = { menu = false; askExpiry = true })
                            DropdownMenuItem({ Text(stringResource(R.string.main_delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (d != null && meta?.reachOutDays != null) {
                ExtendedFloatingActionButton(
                    onClick = { logDialog = true },
                    icon = { Icon(Icons.Rounded.Handshake, null) },
                    text = { Text(stringResource(R.string.circle_log_interaction)) },
                )
            }
        },
    ) { padding ->
        if (d == null) {
            if (loaded) Text(stringResource(R.string.detail_gone), Modifier.padding(padding).padding(24.dp))
            return@ParleyScaffold
        }
        val primary = d.phones.primary()
        val r = reach(d)
        // Messenger rows grouped per app and number (Reach via apps).
        val reachGroups = remember(messengers) { r.groups(PhoneEnv.countryIso(context)) }
        // The quick actions, shared by the big tiles and the pinned bar.
        val preferredCall = messengers.firstOrNull { it.accountType == prefs.call && it.isCall && !it.isVideo }
        val canCall = primary != null || preferredCall != null
        fun doCall() {
            if (preferredCall != null) ContactMessaging.start(context, preferredCall.intent(), preferredCall.appName)?.let { vm.toast(it) }
            else primary?.let { callPeek(it.value, d.displayName) }
        }
        val canMessage = primary != null || r.linked.isNotEmpty()
        val preferredVideo = r.videoRows.firstOrNull { it.accountType == prefs.video }
        fun doVideo() {
            val only = r.videoRows.distinctBy { it.accountType }.singleOrNull()
            val target = preferredVideo ?: only
            if (target != null) ContactMessaging.startRow(context, r, target)?.let { vm.toast(it) } else messageSheet = primary?.value.orEmpty()
        }
        val email = d.emails.primary()
        val sections = PageSections()
        val today = remember { LocalDate.now() }
        val sep = stringResource(R.string.main_separator)
        val region = PhoneEnv.countryIso(context)
        val sameLine: (String, String) -> Boolean = { a, b -> PhoneIdentity.same(a, b, region) }
        // Dates that come round again (a date of death doesn't), with their place in d.events.
        val dated = remember(d.events) {
            // A date kept by another calendar counts from its next Gregorian day.
            d.events.mapIndexedNotNull { i, ev ->
                EventDate.parse(ev.date)?.takeUnless { LifeEvents.isDeath(ev.type, ev.label) }?.let { effectiveDate(it, ev.calendar, today) }?.let { i to it }
            }
        }
        fun dateText(i: Int, days: Long): String {
            val label = eventLabel(resources, d.events[i])
            return when (days) {
                0L -> resources.getString(R.string.contact_page_date_today, label)
                1L -> resources.getString(R.string.contact_page_date_tomorrow, label)
                else -> resources.getQuantityString(R.plurals.contact_page_date_in, days.toInt(), label, days.toInt())
            }
        }
        // Stay in touch first when there's something to say: the rhythm (Circle), a good time to call, promises.
        // Outside the Circle, "Add to your Circle" waits in the settings group and the next date is in the header.
        val inCircle = meta?.reachOutDays != null
        val stayHasNews = inCircle || goodTime != null || memory.promises.isNotEmpty()
        if (d.lookupKey.isNotEmpty() && stayHasNews) {
            sections.add(ContactSection.STAY, sectionTitle(resources, ContactSection.STAY), lastTalked) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    StayInTouchCard(meta, d, history, interactions, goodTime = goodTime, title = null, showNext = false, invite = false) { reachOut = true }
                    if (memory.promises.isNotEmpty()) PromisesCard(vm, d.lookupKey, memory)
                }
            }
        }
        // Contact info: numbers (with the apps that reach each one), emails, addresses, then what's left of
        // "Message or call on…" (typed-in handles, apps on numbers not saved here).
        if (d.phones.isNotEmpty()) {
            val summary = if (d.phones.size == 1) Bidi.ltr(Format.number(d.phones[0].value, vm.countryIso)) else resources.getQuantityString(R.plurals.contact_page_count_numbers, d.phones.size, d.phones.size)
            sections.addRows(ContactSection.PHONES, sectionTitle(resources, ContactSection.PHONES), summary) {
                d.phones.forEachIndexed { i, p ->
                    item {
                        val pinned = simPrefs.firstOrNull { PhoneIdentity.matchesStored(it.matchKey, p.value, vm.countryIso) }?.phoneAccountId
                        val apps = remember(reachGroups, p.value) { ReachGroups.appNamesFor(reachGroups, p.value, sameLine) }
                        val label = listOfNotNull(
                            Format.phoneType(resources, p.type, p.label).ifBlank { null },
                            resources.getString(R.string.contact_page_default).takeIf { p.isPrimary && d.phones.size > 1 },
                            pinned?.let { id -> sims.firstOrNull { it.id == id }?.label?.let { resources.getString(R.string.detail_always_sim, it) } },
                            apps.takeIf { it.isNotEmpty() }?.joinToString(resources.getString(R.string.contact_page_list_separator)),
                        ).joinToString(sep)
                        PhoneRow(
                            vm, p, first = i == 0, label = label,
                            canDefault = d.phones.size > 1 && p.id != null,
                            multiSim = sims.size > 1,
                            hasApps = apps.isNotEmpty(),
                            // Its icon on the Message button only while it's there to open (else a text is sent).
                            usualApp = remember(prefs.message) {
                                prefs.message?.let(MessengerApp::forPackage)?.takeIf { it.packageName in ContactMessaging.installed(context) }
                            },
                            onCall = { callPeek(p.value, d.displayName) },
                            onMessage = { messageNumber(d, p.value) },
                            onMessageOn = { messageSheet = p.value },
                            onSim = { simFor = p.value },
                            onDefault = { on -> page.setDefault(p, Phone.CONTENT_ITEM_TYPE, on) },
                        )
                    }
                }
            }
        }
        if (d.emails.isNotEmpty()) {
            val summary = if (d.emails.size == 1) d.emails[0].value else resources.getQuantityString(R.plurals.contact_page_count_emails, d.emails.size, d.emails.size)
            sections.addRows(ContactSection.EMAILS, sectionTitle(resources, ContactSection.EMAILS), summary) {
                d.emails.forEachIndexed { i, e ->
                    item {
                        val label = listOfNotNull(
                            Format.emailType(resources, e.type, e.label).ifBlank { null },
                            resources.getString(R.string.contact_page_default).takeIf { e.isPrimary && d.emails.size > 1 },
                        ).joinToString(sep)
                        GroupDataRow(
                            Icons.Rounded.Email, i == 0, e.value, label, onClick = { Intents.email(context, e.value) },
                            menu = if (d.emails.size > 1 && e.id != null) ({ close ->
                                DefaultMenuItem(e.isPrimary) { on -> close(); page.setDefault(e, Email.CONTENT_ITEM_TYPE, on) }
                            }) else null,
                        )
                    }
                }
            }
        }
        val mapLinks = remember(d.addresses, d.websites) { AddressMapLinks.matches(d) }
        if (d.addresses.isNotEmpty()) {
            val summary = if (d.addresses.size == 1) d.addresses[0].formatted.lines().joinToString(", ") { it.trim() } else resources.getQuantityString(R.plurals.contact_page_count_addresses, d.addresses.size, d.addresses.size)
            sections.addRows(ContactSection.ADDRESSES, sectionTitle(resources, ContactSection.ADDRESSES), summary) {
                d.addresses.forEachIndexed { i, a ->
                    val link = mapLinks[i]?.let { d.websites.getOrNull(it)?.value }
                    item { AddressDetailRow(a, i == 0, StructuredPostal.getTypeLabel(resources, a.type, a.label).toString(), link) }
                }
            }
        }
        // Apps on a saved number show on that number's row; only the others get rows of their own here.
        val looseApps = remember(reachGroups, d.phones) { ReachGroups.notOnNumbers(reachGroups, d.phones.map { it.value }, sameLine) }
        val handles = d.handles.filter { it.value.isNotBlank() }
        if (handles.isNotEmpty() || looseApps.isNotEmpty()) {
            val apps = looseApps.map { it.appLabel }.distinct()
            val summary = if (apps.isNotEmpty()) apps.joinToString(", ")
            else resources.getQuantityString(R.plurals.contact_page_count_items, handles.size, handles.size)
            sections.addRows(
                ContactSection.MESSENGERS, sectionTitle(resources, ContactSection.MESSENGERS), summary,
                after = { CoachMark(Tips.REACH_USUAL, stringResource(R.string.reach_reach_hint), enabled = looseApps.isNotEmpty()) },
            ) {
                // Handles typed into the contact (Matrix, Threema, Signal username…).
                handleRows(handles, Icons.Rounded.Forum, onWeb = { webLink = it })
                // Each remaining app's Message / Voice / Video for this person (long-press: make it the usual way).
                reachViaAppsRows(
                    looseApps, prefs, showNumbers = true,
                    onOpen = { row -> r.action(row)?.let { m -> ContactMessaging.startRow(context, r, m)?.let { vm.toast(it) } } },
                    onToggleUsual = { row -> savePrefs(prefs.toggleUsual(row)) },
                )
            }
        }
        // Profiles (Instagram, LinkedIn…): website rows that name a service, each opened in its app or the browser.
        val profiles = remember(d.websites) {
            d.websites.mapNotNull { w -> SocialProfiles.fromWebsite(w.value, w.type, w.label)?.takeIf { it.handle.isNotBlank() } }
        }
        if (profiles.isNotEmpty()) {
            val summary = profiles.map { it.service.label }.distinct().joinToString(", ")
            sections.addRows(ContactSection.PROFILES, sectionTitle(resources, ContactSection.PROFILES), summary) { profileRows(profiles) }
        }
        // About them: dates, websites, relations, the contact's own note, then Parley's note for calls.
        val quickDates = can(ContactCapability.QUICK_DATES) && hasMissingDates(d)
        if (d.events.isNotEmpty() || quickDates) {
            val next = ContactPage.nextDate(dated.map { it.second }, today)?.let { (j, days) -> dated[j].first to days }
            val summary = next?.let { (i, days) -> dateText(i, days) }
                ?: if (d.events.isNotEmpty()) resources.getQuantityString(R.plurals.contact_page_count_dates, d.events.size, d.events.size) else ""
            sections.addRows(ContactSection.DATES, sectionTitle(resources, ContactSection.DATES), summary) {
                val yearly = YearlyEvents.decode(meta?.yearlyEvents)
                d.events.forEachIndexed { i, ev ->
                    item {
                        // A life event (new job, moved…) can be remembered yearly in the digest.
                        val date = EventDate.parse(ev.date)
                        val canYearly = date != null && d.lookupKey.isNotEmpty() && YearlyEvents.eligible(ev.type) &&
                            !LifeEvents.isDeath(ev.type, ev.label)
                        val key = if (canYearly) YearlyEvents.key(ev.type, ev.label, date!!) else null
                        val on = key != null && key in yearly
                        GroupDataRow(
                            Icons.Rounded.Cake, i == 0, describeCalendarEvent(resources, ev.date, ev.calendar, today) ?: describeLifeEvent(resources, d, ev),
                            eventLabel(resources, ev) + (if (on) sep + resources.getString(R.string.circle_yearly_label) else ""),
                            onClick = {},
                            trailing = if (key == null) null else ({
                                IconButton({ page.setYearly(key, !on) }) {
                                    Icon(
                                        Icons.Rounded.EventRepeat,
                                        stringResource(if (on) R.string.circle_yearly_stop else R.string.circle_yearly_remember),
                                        tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }),
                        )
                    }
                }
                // Empty birthday / anniversary slots, saved straight to the system contact.
                if (quickDates) item {
                    MissingDateChips(vm, d, onSaved = page::reload, modifier = Modifier.padding(vertical = Spacing.xs), save = { e -> page.addDate(d, e) })
                }
            }
        }
        // An address's map link opens from the address itself, so it isn't listed again as a website.
        // Profiles have their own group above.
        val sites = d.websites.filterIndexed { i, w -> i !in mapLinks.values && SocialProfiles.fromWebsite(w.value, w.type, w.label)?.handle.isNullOrBlank() }
        val relationCount = d.relations.size + parleyRelations.size + relationsFromOthers.size
        if (sites.isNotEmpty() || d.note.isNotBlank() || relationCount > 0) {
            val n = sites.size + relationCount + (if (d.note.isNotBlank()) 1 else 0)
            sections.addRows(ContactSection.ABOUT, resources.getString(R.string.detail_about, d.given.ifBlank { d.displayName }), resources.getQuantityString(R.plurals.contact_page_count_items, n, n)) {
                sites.forEachIndexed { i, w ->
                    item {
                        val label = resources.getString(R.string.detail_website)
                        GroupDataRow(Icons.Rounded.Language, i == 0, w.value, label, onClick = { Intents.web(context, w.value) })
                    }
                }
                d.relations.forEachIndexed { i, rel ->
                    item {
                        val type = RelationTypes.fromAndroid(rel.type, rel.label)
                        val label = relationLabel(resources, type?.key, type?.let { RelationText.label(resources, it) })
                            ?: ContactsContract.CommonDataKinds.Relation.getTypeLabel(resources, rel.type, rel.label).toString()
                        GroupDataRow(Icons.Rounded.People, i == 0, rel.value, label, onClick = { openRelation(rel.value) })
                    }
                }
                // Relations kept in Parley only: the same rows, saying where they live.
                parleyRelations.forEachIndexed { i, rel ->
                    item {
                        val type = RelationTypes.fromAndroid(rel.type, rel.label)
                        val label = relationLabel(resources, type?.key, type?.let { RelationText.label(resources, it) })
                            ?: ContactsContract.CommonDataKinds.Relation.getTypeLabel(resources, rel.type, rel.label).toString()
                        GroupDataRow(
                            Icons.Rounded.People, d.relations.isEmpty() && i == 0, rel.value, resources.getString(R.string.detail_relation_parley_only, label),
                            onClick = { openRelation(rel.value) },
                        )
                    }
                }
                // A private contact's relation to this one (or this private contact's from another): shown, never written
                // where other apps could read it (RelationsFromOthers).
                relationsFromOthers.forEachIndexed { i, other ->
                    item {
                        val known = other.row.typeKey?.let(RelationTypes::byKey)?.let { RelationText.label(resources, it) }
                        val type = relationLabel(resources, other.row.typeKey, known) ?: other.row.label.orEmpty()
                        GroupDataRow(
                            Icons.Rounded.People, d.relations.isEmpty() && parleyRelations.isEmpty() && i == 0, other.row.name,
                            resources.getString(R.string.detail_relation_from_them, type),
                            onClick = { open(Routes.contact(other.navId)) },
                        )
                    }
                }
                if (d.note.isNotBlank()) item {
                    GroupDataRow(Icons.AutoMirrored.Rounded.Notes, true, d.note, resources.getString(R.string.detail_note), onClick = {}, headline = { LinkifiedText(d.note) })
                }
            }
        }
        // Custom fields, the language, RFC 9554's name and address parts.
        val more = remember(d) { moreFacts(resources, d) }
        if (more.isNotEmpty()) {
            sections.addRows(ContactSection.MORE, sectionTitle(resources, ContactSection.MORE), resources.getQuantityString(R.plurals.contact_page_count_items, more.size, more.size)) {
                more.forEachIndexed { i, f -> item { GroupDataRow(Icons.Rounded.Info, i == 0, f.value, f.label, onClick = null) } }
            }
        }
        val note = meta?.pinnedNote
        sections.addRows(ContactSection.NOTE, sectionTitle(resources, ContactSection.NOTE), note?.lineSequence()?.firstOrNull().orEmpty().ifBlank { resources.getString(R.string.contact_page_no_note) }) {
            item {
                InfoRow(
                    // Tap edits it; press and hold copies it, like the page's other facts.
                    modifier = Modifier.combinedClickable(
                        onClick = { editNote = true },
                        onLongClick = note?.let { n -> { Clipboard.copy(context, n) } },
                        onLongClickLabel = note?.let { stringResource(R.string.main_copy) },
                    ),
                    leading = {
                        val cs = MaterialTheme.colorScheme
                        Icon(Icons.Rounded.PushPin, null, tint = if (note != null) cs.primary else cs.onSurfaceVariant)
                    },
                    headline = { Text(note ?: stringResource(R.string.detail_add_note)) },
                    supporting = { Text(stringResource(if (note != null) R.string.detail_note_shown else R.string.detail_note_hint)) },
                )
            }
        }
        val notes = ui.notes
        // Calls, logged interactions, call notes and dates. P1: the latest few; "Show all" opens the rest.
        val timelineCount = remember(history, interactions, notes, d.events) { timelineEntries(d, history, interactions, notes, ZoneId.systemDefault()).size }
        sections.add(ContactSection.TIMELINE, sectionTitle(resources, ContactSection.TIMELINE), resources.getQuantityString(R.plurals.contact_page_entries, timelineCount, timelineCount)) {
            ContactTimeline(
                vm, d, history, interactions, notes, onEdit = { editEntry = it },
                // A number's own history screen lists the phone's call history; a private contact's calls are all here.
                onAllCalls = primary?.takeIf { history.size > 5 && !isPrivate }?.let { p -> { open(Routes.history(p.value)) } },
                limit = TIMELINE_PREVIEW, onShowAll = { open(ContactPageRoutes.timeline(contactId)) }, showTitle = false,
            )
        }
        if (history.isNotEmpty()) sections.add(ContactSection.INSIGHTS, sectionTitle(resources, ContactSection.INSIGHTS), resources.getQuantityString(R.plurals.contact_page_count_calls, history.size, history.size)) {
            OnGroupSurface { CallInsightsSection(vm, d.phones.map { it.value }, showTitle = false, index = ui.privateIndex) }
        }
        if (otherFields.isNotEmpty()) sections.addRows(
            ContactSection.OTHER, sectionTitle(resources, ContactSection.OTHER),
            resources.getQuantityString(R.plurals.contact_page_count_items, otherFields.size, otherFields.size),
            after = { GroupNote(stringResource(R.string.detail_other_fields_note)) },
        ) {
            otherFields.forEachIndexed { i, f -> item { GroupDataRow(Icons.Rounded.Info, i == 0, f.value, f.label, onClick = null) } }
        }
        // Everything that changes how Parley and the phone treat this person rather than describing them.
        sections.addRows(
            ContactSection.SETTINGS, sectionTitle(resources, ContactSection.SETTINGS), resources.getString(R.string.contact_page_settings_summary),
            // A private contact's ringtone and "Send to voicemail" need Parley's own ringer: said here when it isn't.
            after = if (isPrivate) ({ DefaultAppNote(vm, DefaultAppFeature.PRIVATE_CALLER) }) else null,
        ) {
            if (d.lookupKey.isNotEmpty() && !inCircle) item {
                InfoRow(
                    modifier = Modifier.clickable { reachOut = true },
                    leading = { Icon(Icons.Rounded.Handshake, null) },
                    headline = { Text(stringResource(R.string.circle_add_to_circle)) },
                    supporting = { Text(stringResource(R.string.circle_add_to_circle_body)) },
                )
            }
            if (can(ContactCapability.SEND_TO_VOICEMAIL)) item {
                InfoRow(
                    modifier = Modifier.toggleable(d.sendToVoicemail, role = Role.Switch, onValueChange = { v -> page.setSendToVoicemail(v) }),
                    leading = { Icon(Icons.Rounded.Voicemail, null) },
                    headline = { Text(stringResource(R.string.detail_send_to_voicemail)) },
                    trailing = { Switch(d.sendToVoicemail, onCheckedChange = null, modifier = Modifier.padding(end = Spacing.m)) },
                )
            }
            if (can(ContactCapability.CALL_TIME)) blended { ContactCallTimeRows(vm, d.lookupKey, d.displayName, d.starred) }
            if (can(ContactCapability.RINGTONE)) item {
                val tone = d.customRingtone?.let {
                    // A tune made from the name has a hash for a file name; say whose it is instead.
                    if (CallerTunes.isOurs(context, it)) resources.getString(R.string.caller_tune_made_for, d.displayName)
                    else runCatching { RingtoneManager.getRingtone(context, Uri.parse(it))?.getTitle(context) }.getOrNull()
                }
                GroupDataRow(Icons.Rounded.MusicNote, true, tone ?: resources.getString(R.string.detail_default_ringtone), resources.getString(R.string.detail_ringtone), onClick = {
                    ringtonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE))
                })
            }
            // Haptic caller ID and auto-answer for this person (Parley applies both, private contacts included).
            if (d.lookupKey.isNotEmpty()) blended {
                val onTune: ((Uri) -> Unit)? = if (can(ContactCapability.RINGTONE)) { uri ->
                    page.setRingtone(uri)
                    vm.toast(resources.getString(R.string.caller_tune_set, d.displayName))
                } else null
                CallerChoiceRows(vm, d.lookupKey, d.displayName, onTune)
            }
            blended { CallBackgroundInfoRow(vm, d) }
            blended { CallPhotoRow(vm, d) }
            // Where it's saved, as chips with their own actions (edit this copy, move, unlink); a private contact is
            // kept only in Parley.
            if (!can(ContactCapability.ACCOUNTS)) item {
                InfoRow(
                    leading = { Icon(Icons.Rounded.Lock, null) },
                    headline = { Text(stringResource(R.string.detail_saved_in)) },
                    supporting = { Text(stringResource(R.string.contact_saved_private)) },
                )
            }
            if (can(ContactCapability.ACCOUNTS)) item {
                InfoRow(
                    leading = { Icon(Icons.Rounded.Sync, null) },
                    headline = {
                        val n = d.rawContacts.size
                        Text(if (n > 1) resources.getQuantityString(R.plurals.detail_linked_from, n, n) else resources.getString(R.string.detail_saved_in))
                    },
                    supporting = {
                        AccountChips(vm, d, open) { newId ->
                            if (newId != null && newId != contactId) { back(); open(Routes.contact(newId)) }
                            else page.reload()
                        }
                    },
                )
            }
            if (can(ContactCapability.ACCOUNTS)) blended { ProvenanceRow(vm, contactId, d, open) }
            // The variants, converted both ways from here (and from ⋮): private ⇄ visible, temporary ⇄ permanent.
            item {
                GroupDataRow(
                    if (isPrivate) Icons.Rounded.LockOpen else Icons.Rounded.Lock, true,
                    resources.getString(if (isPrivate) R.string.contact_make_visible else R.string.detail_move_vault),
                    resources.getString(if (isPrivate) R.string.contact_make_visible_summary else R.string.contact_make_private_summary),
                    onClick = { if (isPrivate) confirmVisible = true else confirmPrivate = true },
                )
            }
            if (temp == null) item {
                GroupDataRow(
                    Icons.Rounded.Timer, true, resources.getString(R.string.contact_make_temporary),
                    resources.getString(R.string.contact_make_temporary_summary), onClick = { askExpiry = true },
                )
            }
            temp?.let { t ->
                item {
                    GroupDataRow(
                        Icons.Rounded.Timer, true, resources.getString(R.string.detail_deletes_on, Format.fullDate(context, t.expiresAt)),
                        resources.getString(R.string.detail_change_expiry), onClick = { askExpiry = true },
                    )
                }
                item {
                    GroupDataRow(
                        Icons.Rounded.Timer, true, resources.getString(R.string.contact_keep_permanently),
                        resources.getString(R.string.contact_keep_permanently_summary), onClick = { page.setExpiry(null) },
                    )
                }
            }
            item {
                GroupDataRow(
                    Icons.Rounded.ViewAgenda, true, resources.getString(R.string.contact_page_settings_title),
                    resources.getString(R.string.set_contact_page_summary), onClick = { open(ContactPageRoutes.Sections) },
                )
            }
        }
        val blocks = if (locked) emptyList() else sections.blocks(layout)
        fun blockTitle(b: PageBlock): String = when (b.family) {
            SectionFamily.CONTACT_INFO -> resources.getString(R.string.contact_page_info)
            SectionFamily.ABOUT -> resources.getString(R.string.detail_about, d.given.ifBlank { d.displayName })
            null -> sections.titleOf(b.sections.first())
        }
        // At a glance under the name: last talked, the next date when it's close, open promises.
        val glance = remember(talked, dated, memory.promises.size, today) {
            ContactGlance.facts(talked?.date, dated.map { it.second }, today, memory.promises.size)
        }
        val glanceText = glance.joinToString(sep) { f ->
            when (f) {
                is GlanceFact.LastTalked, GlanceFact.NoCalls -> lastTalked
                is GlanceFact.NextDate -> dateText(dated[f.index].first, f.days)
                is GlanceFact.OpenPromises -> resources.getQuantityString(R.plurals.contact_page_open_promises, f.count, f.count)
            }
        }

        Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            item(key = "header") {
                Column(
                    Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }.padding(horizontal = Spacing.l).padding(top = Spacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Shrinks towards the bar and fades as it scrolls under it, where the small avatar and name appear.
                    Column(
                        Modifier.graphicsLayer {
                            val s = 1f - 0.45f * headerFraction
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0.5f, 0f)
                            alpha = 1f - headerFraction
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        HeroPhoto(
                            vm, d.displayName, d.photoUri, original, heroSize, Modifier.shared("avatar-$contactId"),
                            isCompany = d.composedName.isBlank() && d.company.isNotBlank(),
                        ) { showPhoto = true }
                        // Press and hold the name to copy it (the name only, not the lines under it).
                        HeaderName(d.displayName, Modifier.padding(top = Spacing.m).shared("name-$contactId", bounds = true))
                    }
                    // Pronouns first, right under the name; each part copies itself when tapped.
                    val work = listOf(d.title, d.department, d.company).filter { it.isNotBlank() }.joinToString(", ")
                    // "Married to Sam" / "Partner of Alex" from the relations (a tap opens them; a former spouse only
                    // shows on the relation's own row).
                    val status = remember(d.relations, parleyRelations, relationsFromOthers) {
                        val own = (d.relations + parleyRelations).map { rel -> RelationMirrors.rowOf(rel) to { openRelation(rel.value) } }
                        val others = relationsFromOthers.map { o -> o.row to { open(Routes.contact(o.navId)) } }
                        RelationshipStatus.header(own + others) { it.first }.map { (kind, item) ->
                            val res = if (kind == RelationshipStatus.Kind.MARRIED) R.string.detail_married_to else R.string.detail_partner_of
                            HeaderLink(resources.getString(res, item.first.name.trim()), item.second)
                        }
                    }
                    HeaderFacts(listOf(d.pronouns.trim(), d.nickname.trim(), work), sep, status)
                    Text(
                        glanceText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.xxs),
                    )
                    // Private and temporary show as small chips; nothing else on the page looks different.
                    VariantChips(ui.variants, onClick = { chip ->
                        when (chip) {
                            VariantChip.Private -> if (locked) unlock() else confirmVisible = true
                            is VariantChip.Temporary -> askExpiry = true
                        }
                    })
                    Spacer(Modifier.height(Spacing.m))
                    // Labelled tiles.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        // Press and hold Call (through the phone) for "Call with a reason…" (I12).
                        val reasonNumber = primary?.value?.takeIf { preferredCall == null && CallReason.offered(it) }
                        ActionTile(
                            Icons.Rounded.Call, if (preferredCall != null) preferredCall.appName else stringResource(R.string.main_call), canCall,
                            onLongClick = reasonNumber?.let { n -> { reasonFor = ReasonTarget(n, d.displayName) } },
                            longClickLabel = stringResource(R.string.reason_call_with),
                        ) { doCall() }
                        val messageApp = prefs.message?.let { p -> if (p == MessengerPrefs.SMS) stringResource(R.string.detail_sms) else messengers.firstOrNull { it.accountType == p }?.appName ?: MessengerApp.forPackage(p)?.label }
                        ActionTile(
                            Icons.AutoMirrored.Rounded.Message, messageApp ?: stringResource(R.string.main_message), canMessage,
                            onLongClick = { messageSheet = primary?.value.orEmpty() }, longClickLabel = stringResource(R.string.detail_choose_message),
                        ) { message(d) }
                        if (r.videoRows.isNotEmpty()) {
                            ActionTile(
                                Icons.Rounded.Videocam, preferredVideo?.appName ?: stringResource(R.string.detail_video), true,
                                onLongClick = { messageSheet = primary?.value.orEmpty() }, longClickLabel = stringResource(R.string.detail_choose_video),
                            ) { doVideo() }
                        }
                        ActionTile(Icons.Rounded.Email, stringResource(R.string.detail_email), email != null) {
                            email?.let { Intents.email(context, it.value) }
                        }
                    }
                }
            }
            // I14: a newer signed card from this person, waiting for review (never applied by itself).
            if (ui.access == PrivateAccess.OPEN && d.lookupKey.isNotEmpty()) item(key = "card_update") { CardUpdateBanner(vm, contactId, d.lookupKey, d) }
            // I6: menu shortcuts saved for this person's numbers (from the call screen's keypad).
            if (!locked && d.phones.isNotEmpty()) item(key = "menu_shortcuts") { MenuShortcutsBlock(vm, d.phones.map { it.value }, d.displayName, d.photoUri) }
            // A private contact while the vault is locked: its name, photo and numbers only, and the unlock right here.
            if (ui.access != PrivateAccess.OPEN && ui.access != PrivateAccess.OPENING) item(key = "access") {
                PrivateAccessRow(ui.access, onUnlock = ::unlock, onRetry = page::reload, onKeep = page::keepWhatIsLeft)
            }
            // Every section folds; order, start modes and hidden ones come from Settings › Contacts › Contact page sections.
            if (!locked) foldableSections(sections, layout, ::blockTitle, ::fold)
        }
        // Once the big header has gone, a compact bar keeps the actions (and, on long pages, jumps to a section).
        AnimatedVisibility(
            pinned, Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
            enter = expandVertically(ParleyMotion.spatial(), expandFrom = Alignment.Top) + fadeIn(ParleyMotion.effects()),
            exit = shrinkVertically(ParleyMotion.fastSpatial(), shrinkTowards = Alignment.Top) + fadeOut(ParleyMotion.fastEffects()),
        ) {
            // Only the ways to reach them, as on the big tiles: star, edit and ⋮ stay in the top bar above (with the
            // docked name and photo), so no action shows twice once the page has scrolled.
            val actions = listOfNotNull(
                QuickAction(Icons.Rounded.Call, stringResource(R.string.main_call_who, d.displayName), canCall) { doCall() },
                QuickAction(Icons.AutoMirrored.Rounded.Message, stringResource(R.string.main_message_who, d.displayName), canMessage) { message(d) },
                if (r.videoRows.isNotEmpty()) {
                    QuickAction(Icons.Rounded.Videocam, preferredVideo?.appName ?: stringResource(R.string.detail_video), true) { doVideo() }
                } else null,
                if (email != null) QuickAction(Icons.Rounded.Email, stringResource(R.string.detail_email), true) { Intents.email(context, email.value) } else null,
            )
            val jumps = if (peopleSettings.sectionChips && blocks.size >= JUMP_CHIPS_FROM) blocks.map { b ->
                blockTitle(b) to {
                    if (layout.isFolded(b)) fold(b, false)
                    scope.launch {
                        listState.animateScrollToItem(1 + blocks.indexOf(b))
                        listState.animateScrollBy(-pinnedHeight.toFloat())
                    }
                    Unit
                }
            } else emptyList()
            Surface(color = barColor, modifier = Modifier.onSizeChanged { pinnedHeight = it.height }) {
                PinnedContactBar(actions, jumps)
            }
        }
        }

        messageSheet?.let { n ->
            ReachSheet(
                ReachTarget.Person(r.copy(defaultNumber = n.ifEmpty { r.defaultNumber })) { p -> savePrefs(p) },
                onDismiss = { messageSheet = null },
                onCall = { num -> callPeek(num, r.name) },
            )
        }
        webLink?.let { l -> ConfirmWebLink(l) { webLink = null } }
        if (showQr) QrDialog(vm, d, isPrivate) { showQr = false }
        // A private contact's Parley key isn't part of what it shares.
        if (secureQr) SecureQrDialog(vm, if (isPrivate) d.copy(id = 0, lookupKey = "") else d, isPrivate) { secureQr = false }
        if (copyToSim) CopyToSimDialog(vm, d) { copyToSim = false }
        if (editNote) {
            var text by remember { mutableStateOf(TextFieldValue(meta?.pinnedNote.orEmpty())) }
            ConfirmDialog(
                title = stringResource(R.string.detail_note_title),
                text = null,
                confirmLabel = stringResource(R.string.main_save),
                onConfirm = { editNote = false; page.setPinnedNote(text.text) },
                onDismiss = { editNote = false },
                dismissLabel = stringResource(R.string.main_cancel),
                content = { PromiseNoteField(text, { text = it }, placeholder = stringResource(R.string.detail_note_placeholder)) },
            )
        }
        if (reachOut) RhythmDialog(vm, d, contactId, meta) { reachOut = false }
        reasonFor?.let { t -> CallReasonFlow(vm, t) { reasonFor = null } }
        peekNumber?.let { n ->
            PreCallPeekSheet(
                vm, d.lookupKey, d.given.ifBlank { d.displayName }, memory, goodTime,
                onCall = { peekNumber = null; vm.requestCall(n, d.displayName) },
                onDismiss = { peekNumber = null },
            )
        }
        if (remindToCall) d.phones.primary()?.let { p -> RemindToCallSheet(vm, p.value, d.displayName, onDismiss = { remindToCall = false }) }
        if (logDialog || editEntry != null) {
            val initial = editEntry
            LogInteractionDialog(d.given.ifBlank { d.displayName }, initial, onDismiss = { logDialog = false; editEntry = null }) { type, note, time ->
                logDialog = false
                editEntry = null
                page.saveInteraction(initial, type, note, time)
            }
        }
        if (pinDialog) {
            ParleyDialog(
                onDismissRequest = { pinDialog = false },
                title = { Text(stringResource(R.string.detail_add_home)) },
                text = {
                    Column {
                        d.phones.forEach { p ->
                            ParleyListItem(headlineContent = { Text(stringResource(R.string.main_call_who, Bidi.ltr(Format.number(p.value, vm.countryIso)))) }, leadingContent = { Icon(Icons.Rounded.Call, null) }, modifier = Modifier.clickable {
                                pinDialog = false
                                Shortcuts.pin(context, Shortcuts.Kind.CALL, d.displayName, p.value, contactId, d.photoUri)
                            })
                            ParleyListItem(headlineContent = { Text(stringResource(R.string.main_message_who, Bidi.ltr(Format.number(p.value, vm.countryIso)))) }, leadingContent = { Icon(Icons.AutoMirrored.Rounded.Message, null) }, modifier = Modifier.clickable {
                                pinDialog = false
                                Shortcuts.pin(context, Shortcuts.Kind.MESSAGE, d.displayName, p.value, contactId, d.photoUri)
                            })
                        }
                        ParleyListItem(headlineContent = { Text(stringResource(R.string.main_open_contact)) }, leadingContent = { Icon(Icons.Rounded.Person, null) }, modifier = Modifier.clickable {
                            pinDialog = false
                            Shortcuts.pin(context, Shortcuts.Kind.OPEN, d.displayName, null, contactId, d.photoUri, d.lookupKey)
                        })
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ pinDialog = false }) { Text(stringResource(R.string.main_cancel)) } },
            )
        }
        relationChoice?.let { ids ->
            ParleyDialog(
                onDismissRequest = { relationChoice = null },
                title = { Text(stringResource(R.string.detail_which_contact)) },
                text = {
                    Column {
                        ids.forEach { ct ->
                            val id = ct.id
                            ParleyListItem(
                                modifier = Modifier.clickable {
                                    relationChoice = null
                                    open(Routes.contact(id))
                                },
                                headlineContent = { Text(ct.displayName) },
                                supportingContent = { ct.phones.firstOrNull()?.let { Text(Bidi.ltr(Format.number(it.number, vm.countryIso))) } },
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ relationChoice = null }) { Text(stringResource(R.string.main_cancel)) } },
            )
        }
        if (askExpiry) ExpiryDialog(onDismiss = { askExpiry = false }) { days ->
            askExpiry = false
            page.setExpiry(days)
        }
        d.photoUri?.takeIf { showPhoto }?.let { uri ->
            val export = contactPhotoImage(vm, d.displayName, original, uri, contactId.takeUnless { isPrivate }, isPrivate)
            original?.let { OriginalPhotoViewer(vm, it, export) { showPhoto = false } }
                ?: PhotoViewer(vm, uri, export, stringResource(R.string.detail_contact_photo)) { showPhoto = false }
        }
        if (confirmDelete) {
            ConfirmDialog(
                title = if (isPrivate) stringResource(R.string.vault_delete_title) else stringResource(R.string.detail_delete_title, d.displayName),
                // A private contact's copy is kept sealed ("Deleted private contacts" in History & undo), never plain.
                text = stringResource(if (isPrivate) R.string.vault_delete_text else R.string.detail_delete_body),
                confirmLabel = stringResource(R.string.main_delete),
                onConfirm = {
                    confirmDelete = false
                    if (isPrivate) {
                        page.deletePrivate(noCopy = { confirmDeleteNoCopy = true }, then = back)
                    } else {
                        vm.deleteContacts(listOf(contactId))
                        back()
                    }
                },
                onDismiss = { confirmDelete = false },
                destructive = true,
                dismissLabel = stringResource(R.string.main_cancel),
            )
        }
        if (confirmDeleteNoCopy) {
            ConfirmDialog(
                title = stringResource(R.string.vault_delete_no_copy_title),
                text = stringResource(R.string.vault_delete_no_copy_text),
                confirmLabel = stringResource(R.string.vault_delete_no_copy_confirm),
                onConfirm = {
                    confirmDeleteNoCopy = false
                    page.deletePrivate(keepCopy = false, then = back)
                },
                onDismiss = { confirmDeleteNoCopy = false },
                destructive = true,
                dismissLabel = stringResource(R.string.main_cancel),
            )
        }
        if (confirmPrivate) {
            ConfirmDialog(
                title = stringResource(R.string.contact_make_private_title, d.given.ifBlank { d.displayName }),
                text = stringResource(R.string.contact_make_private_body),
                confirmLabel = stringResource(R.string.detail_move_vault),
                icon = Icons.Rounded.Lock,
                onConfirm = {
                    confirmPrivate = false
                    scope.launchVault(
                        context as? ComponentActivity,
                        { e -> vm.toast(resources.getString(R.string.detail_move_failed, UserErrorText.of(context, e))) },
                    ) {
                        // The note for calls and the messaging choice go with them, sealed; the rest is re-keyed.
                        val id = vm.moveToVault(contactId, d.copy(pinnedNote = meta?.pinnedNote.orEmpty(), messengerPrefs = prefs.encode().orEmpty()))
                        vm.toast(resources.getString(R.string.detail_moved_private))
                        back()
                        open(Routes.contact(-id))
                    }
                },
                onDismiss = { confirmPrivate = false },
                dismissLabel = stringResource(R.string.main_cancel),
            )
        }
        if (confirmVisible) {
            ConfirmDialog(
                title = stringResource(R.string.contact_make_visible_title, d.given.ifBlank { d.displayName }),
                text = makeVisibleBody(vm.c.contacts),
                confirmLabel = stringResource(R.string.contact_make_visible_confirm),
                icon = Icons.Rounded.LockOpen,
                onConfirm = {
                    confirmVisible = false
                    scope.launchVault(
                        context as? ComponentActivity,
                        { e -> vm.toast(resources.getString(R.string.vault_move_failed, UserErrorText.of(context, e))) },
                    ) {
                        val s = vm.settings.value
                        // Restores the original contact losslessly when the vault kept its record; else the default account.
                        val requested = AccountRef(s.defaultAccountType, s.defaultAccountName)
                        when (val made = page.makeVisible(requested)) {
                            is ContactConversions.MadeVisible.Done -> {
                                // Where Android 16 put it, when it refused the phone: the account actually written.
                                vm.toast(madeVisibleText(resources, made.redirectedTo))
                                back()
                                open(Routes.contact(made.contactId))
                            }
                            // Nothing changed either way; say why, so the tap isn't simply lost.
                            ContactConversions.MadeVisible.NotWritten -> vm.toast(resources.getString(R.string.contact_make_visible_failed))
                            ContactConversions.MadeVisible.CallsKept -> vm.toast(resources.getString(R.string.contact_make_visible_calls_kept))
                        }
                    }
                },
                onDismiss = { confirmVisible = false },
                dismissLabel = stringResource(R.string.main_cancel),
            )
        }
        if (confirmPrivateQr) {
            ConfirmDialog(
                title = stringResource(R.string.contact_private_qr_title),
                text = stringResource(R.string.contact_private_qr_body),
                confirmLabel = stringResource(R.string.contact_private_qr_confirm),
                icon = Icons.Rounded.QrCode2,
                onConfirm = { confirmPrivateQr = false; showQr = true },
                onDismiss = { confirmPrivateQr = false },
                dismissLabel = stringResource(R.string.main_cancel),
            )
        }
        simFor?.let { number ->
            ParleyDialog(
                onDismissRequest = { simFor = null },
                title = { Text(stringResource(R.string.detail_sim_for, Bidi.ltr(Format.number(number, vm.countryIso)))) },
                text = {
                    Column {
                        ParleyListItem(headlineContent = { Text(stringResource(R.string.detail_sim_ask)) }, modifier = Modifier.clickable { page.setSimFor(number, null); simFor = null })
                        sims.forEach { s ->
                            ParleyListItem(headlineContent = { Text(stringResource(R.string.detail_always_sim, s.label)) }, leadingContent = { Icon(Icons.Rounded.SimCard, null) }, modifier = Modifier.clickable {
                                page.setSimFor(number, s.id); simFor = null
                            })
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ simFor = null }) { Text(stringResource(R.string.main_cancel)) } },
            )
        }
    }
}

/** Timeline entries shown on the page before "Show all" (the full timeline has search and filters). */
private const val TIMELINE_PREVIEW = 3

/** Jump chips appear from this many shown groups. */
private const val JUMP_CHIPS_FROM = 4

/**
 * The contact page's header photo: 128 dp for a real photo and 96 dp for a monogram on a phone held upright
 * (photo, name, the at-a-glance line and the tiles then take about a third of the screen), 160 / 120 dp on
 * tablets, and 88 / 72 dp when the screen is short (a phone in landscape), so the name and the action tiles still
 * fit under it. The sizes are in dp, so large fonts don't crowd the photo.
 */
@Composable
private fun heroPhotoSize(hasPhoto: Boolean): Dp {
    val conf = LocalConfiguration.current
    return when {
        conf.screenHeightDp < 480 -> if (hasPhoto) 88.dp else 72.dp
        conf.screenWidthDp >= 600 && conf.screenHeightDp >= 700 -> if (hasPhoto) 160.dp else 120.dp
        else -> if (hasPhoto) 128.dp else 96.dp
    }
}

@Composable
private fun DefaultMenuItem(isDefault: Boolean, onSet: (Boolean) -> Unit) {
    DropdownMenuItem(
        { Text(stringResource(if (isDefault) R.string.detail_remove_default else R.string.detail_set_default)) },
        // The icon shows the current state, like the star on the number itself.
        leadingIcon = { Icon(if (isDefault) Icons.Rounded.Star else Icons.Rounded.StarOutline, null) },
        onClick = { onSet(!isDefault) },
    )
}

/**
 * One number: tap calls. Trailing, each doing one thing: "Message or call on…" (the apps grid, only when apps reach
 * this number; their names are in the supporting line) opens the sheet of every way to reach it, and Message sends a
 * message to this number straight away: with the contact's usual chat app ([usualApp], whose icon it then shows), or
 * as a text. Long-press: copy, default, message or call on…, edit before calling, SIM.
 *
 * Google Contacts shows only a message icon on a number; Parley keeps the second button only where apps reach the
 * number, because that's where the sheet has more to offer than a text (calls and chats in those apps).
 */
@Composable
private fun PhoneRow(
    vm: AppViewModel,
    p: DataItem,
    first: Boolean,
    label: String,
    canDefault: Boolean,
    multiSim: Boolean,
    hasApps: Boolean,
    usualApp: MessengerApp?,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onMessageOn: () -> Unit,
    onSim: () -> Unit,
    onDefault: (Boolean) -> Unit,
) {
    val shown = Bidi.ltr(Format.number(p.value, vm.countryIso))
    GroupDataRow(
        Icons.Rounded.Call, first, p.value, label, onClick = onCall,
        headline = { Text(shown) },
        trailing = {
            if (hasApps) IconButton(onMessageOn) { Icon(Icons.Rounded.Apps, stringResource(R.string.detail_reach_number, shown)) }
            IconButton(onMessage) {
                if (usualApp != null) {
                    UsualAppIcon(usualApp, stringResource(R.string.detail_message_number_on, shown, usualApp.label))
                } else {
                    Icon(Icons.AutoMirrored.Rounded.Chat, stringResource(R.string.detail_text_number, shown))
                }
            }
        },
        menu = { close ->
            if (canDefault) DefaultMenuItem(p.isPrimary) { on -> close(); onDefault(on) }
            DropdownMenuItem({ Text(stringResource(R.string.reach_message_or_call_on)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Message, null) }, onClick = { close(); onMessageOn() })
            DropdownMenuItem({ Text(stringResource(R.string.detail_edit_before_call)) }, leadingIcon = { Icon(Icons.Rounded.Dialpad, null) }, onClick = {
                close(); vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = p.value))
            })
            if (multiSim) DropdownMenuItem({ Text(stringResource(R.string.detail_choose_sim)) }, leadingIcon = { Icon(Icons.Rounded.SimCard, null) }, onClick = { close(); onSim() })
        },
    )
}

/** The usual chat app's own icon on a number's Message button (the chat bubble when it can't be read). */
@Composable
private fun UsualAppIcon(app: MessengerApp, description: String) {
    val context = LocalContext.current
    val bmp = remember(app.packageName) {
        runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(72, 72).asImageBitmap() }.getOrNull()
    }
    if (bmp != null) Image(bmp, description, Modifier.size(24.dp)) else Icon(Icons.AutoMirrored.Rounded.Chat, description)
}
