package app.parley.ui.contact

import app.parley.ui.Destination
import android.provider.ContactsContract
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.ui.text.input.TextFieldValue
import androidx.fragment.app.FragmentActivity
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Block
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import app.parley.common.people.HandleLink
import app.parley.common.people.LifeEvents
import app.parley.common.people.MessageRoute
import app.parley.common.people.MessengerPrefs
import app.parley.common.people.RelationTypes
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
import app.parley.ui.people.CopyToSimDialog
import app.parley.ui.people.ProvenanceRow
import app.parley.ui.people.RelationText
import app.parley.ui.people.describeLifeEvent
import app.parley.ui.people.eventLabel
import app.parley.ui.screenViewModel
import app.parley.ui.SegmentedGroup
import app.parley.ui.blended
import app.parley.ui.common.Format
import app.parley.ui.common.Intents
import app.parley.ui.shared
import app.parley.security.launchVault
import app.parley.ui.vault.ExpiryDialog
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
import app.parley.ui.ListSectionHeader
import app.parley.ui.Spacing
import app.parley.ui.ParleyMotion
import androidx.compose.material.icons.rounded.LinkOff

/**
 * A contact's page. U1: the photo and name dock into the top bar as you scroll ("last talked" shows there once
 * collapsed); U2: grouped sections; U3: labelled Call / Message / Video / Email tiles; M6/M7: "Message on…" with a
 * remembered choice per person; I1 handles, I3 default number or e-mail, I4 other fields, I5 relation types.
 * Header, actions, Stay in touch, dates, numbers, timeline, notes; "Log interaction" is the FAB for
 * Circle contacts and a ⋮ item for everyone else.
 * Every section folds (its summary shows while folded), in the order and start state chosen in Settings;
 * a compact bar with the quick actions (and jump chips on long pages) stays under the top bar once scrolled; the
 * timeline shows the latest few entries with "Show all" opening the full, searchable one.
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
    var showQr by remember { mutableStateOf(false) }
    var simFor by remember { mutableStateOf<String?>(null) }
    var showPhoto by remember { mutableStateOf(false) }
    var askExpiry by remember { mutableStateOf(false) }
    var relationChoice by remember { mutableStateOf<List<ContactSummary>?>(null) }
    var pinDialog by remember { mutableStateOf(false) }
    var reachOut by remember { mutableStateOf(false) }
    var secureQr by remember { mutableStateOf(false) }
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
    // The pre-call peek (the number about to be called).
    val circleCfg by page.circleConfig.collectAsStateWithLifecycle()
    var peekNumber by remember { mutableStateOf<String?>(null) }
    var editEntry by remember { mutableStateOf<Interaction?>(null) }
    val prefs = ui.prefs
    fun savePrefs(p: MessengerPrefs) = page.setMessengerPrefs(p)
    val temp = ui.temporary

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
        val p = d?.phones?.let { ps -> ps.firstOrNull { it.isPrimary } ?: ps.firstOrNull() }?.value
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
    // A large header photo, like the phone's own contacts apps: a real photo gets the most room, a monogram a little
    // less, and a landscape phone keeps it small enough to leave the actions in view.
    val heroSize = heroPhotoSize(details?.photoUri != null)
    // The header has scrolled away once the name is under the top bar.
    val collapseAt = with(density) { (heroSize + 72.dp).toPx() }
    val collapsed by remember(collapseAt) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > collapseAt } }
    val headerFraction by remember(collapseAt) {
        derivedStateOf { if (listState.firstVisibleItemIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / collapseAt).coerceIn(0f, 1f) }
    }
    // The page's sections (order, start modes, remembered folds); a fold shows at once, then is stored.
    val peopleSettings by vm.people.settings.collectAsStateWithLifecycle()
    var layout by remember { mutableStateOf(peopleSettings.contactPage) }
    LaunchedEffect(peopleSettings.contactPage) { layout = peopleSettings.contactPage }
    fun fold(s: ContactSection, folded: Boolean) {
        layout = layout.withFold(s, folded)
        vm.people.update { it.copy(contactPage = it.contactPage.withFold(s, folded)) }
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
        defaultNumber = (dd.phones.firstOrNull { it.isPrimary } ?: dd.phones.firstOrNull())?.value,
        messengers = messengers,
        prefs = prefs,
        lookupKey = dd.lookupKey,
        contactId = contactId,
    )
    fun message(dd: ContactDetails, number: String? = null) {
        val r = reach(dd).let { if (number != null) it.copy(defaultNumber = number, prefs = it.prefs.copy(number = null)) else it }
        when (val route = ContactMessaging.route(context, r)) {
            MessageRoute.Ask -> messageSheet = number ?: r.defaultNumber ?: ""
            else -> ContactMessaging.open(context, route, r)?.let { vm.toast(it) }
        }
    }

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
                        IconButton({ open(Routes.edit(id = contactId)) }) { Icon(Icons.Rounded.Edit, stringResource(R.string.main_edit)) }
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.main_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            // "Log interaction" is the FAB for Circle contacts; for everyone else it's here.
                            if (meta?.reachOutDays == null) DropdownMenuItem({ Text(stringResource(R.string.circle_log_interaction)) }, leadingIcon = { Icon(Icons.Rounded.Handshake, null) }, onClick = { menu = false; logDialog = true })
                            DropdownMenuItem({ Text(stringResource(R.string.detail_share_file)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = {
                                menu = false; Intents.shareVcard(context, page.vcardUri(d.lookupKey), d.displayName)
                            })
                            DropdownMenuItem({ Text(stringResource(R.string.detail_show_qr)) }, leadingIcon = { Icon(Icons.Rounded.QrCode2, null) }, onClick = { menu = false; showQr = true })
                            DropdownMenuItem({ Text(stringResource(R.string.detail_share_private)) }, leadingIcon = { Icon(Icons.Rounded.Lock, null) }, onClick = { menu = false; secureQr = true })
                            DropdownMenuItem({ Text(stringResource(R.string.detail_versions)) }, leadingIcon = { Icon(Icons.Rounded.History, null) }, onClick = { menu = false; open(Routes.versions(contactId)) })
                            DropdownMenuItem({ Text(stringResource(R.string.detail_add_home)) }, leadingIcon = { Icon(Icons.Rounded.AddToHomeScreen, null) }, onClick = { menu = false; pinDialog = true })
                            if (d.phones.isNotEmpty()) DropdownMenuItem({ Text(stringResource(R.string.detail_copy_sim)) }, leadingIcon = { Icon(Icons.Rounded.SimCard, null) }, onClick = { menu = false; copyToSim = true })
                            DropdownMenuItem({ Text(stringResource(R.string.detail_set_ringtone)) }, leadingIcon = { Icon(Icons.Rounded.MusicNote, null) }, onClick = {
                                menu = false
                                ringtonePicker.launch(
                                    Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, d.customRingtone?.let(Uri::parse)),
                                )
                            })
                            if (d.phones.isNotEmpty()) {
                                DropdownMenuItem({ Text(stringResource(R.string.detail_block_numbers)) }, leadingIcon = { Icon(Icons.Rounded.Block, null) }, onClick = {
                                    menu = false; d.phones.forEach { vm.blockNumber(it.value) }
                                })
                            }
                            ContactPrefixAllowMenuItem(d.composedName.ifBlank { null }, d.phones.map { it.value }) { menu = false }
                            if (d.rawContacts.size > 1) {
                                DropdownMenuItem({ Text(stringResource(R.string.detail_separate)) }, leadingIcon = { Icon(Icons.Rounded.LinkOff, null) }, onClick = {
                                    menu = false; page.separate(back)
                                })
                            }
                            DropdownMenuItem({ Text(stringResource(R.string.detail_move_vault)) }, leadingIcon = { Icon(Icons.Rounded.Lock, null) }, onClick = {
                                menu = false
                                scope.launchVault(context as? FragmentActivity, { e -> vm.toast(resources.getString(R.string.detail_move_failed, e.message.orEmpty())) }) {
                                    // The note for calls and the messaging choice go with them (encrypted).
                                    val id = vm.moveToVault(contactId, d.copy(pinnedNote = meta?.pinnedNote.orEmpty(), messengerPrefs = prefs.encode().orEmpty()))
                                    // Now kept encrypted with them: no plaintext copy stays in Parley's metadata.
                                    page.forgetMeta(d.lookupKey)
                                    vm.toast(resources.getString(R.string.detail_moved_private))
                                    back()
                                    open(Routes.vault(id))
                                }
                            })
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
        val primary = d.phones.firstOrNull { it.isPrimary } ?: d.phones.firstOrNull()
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
        val email = d.emails.firstOrNull { it.isPrimary } ?: d.emails.firstOrNull()
        val sections = PageSections()
        val today = remember { LocalDate.now() }
        // Stay in touch right under the actions (R4: rhythm, last in touch, next date); R9: open promises.
        if (d.lookupKey.isNotEmpty()) sections.add(ContactSection.STAY, sectionTitle(resources, ContactSection.STAY), lastTalked) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StayInTouchCard(meta, d, history, interactions, goodTime = goodTime, title = null) { reachOut = true }
                if (memory.promises.isNotEmpty()) PromisesCard(vm, d.lookupKey, memory)
            }
        }
        // Empty birthday / anniversary slots, saved straight to the system contact.
        if (d.events.isNotEmpty() || hasMissingDates(d)) {
            val dated = d.events.mapIndexedNotNull { i, ev ->
                EventDate.parse(ev.date)?.takeUnless { LifeEvents.isDeath(ev.type, ev.label) }?.let { i to it }
            }
            val next = ContactPage.nextDate(dated.map { it.second }, today)?.let { (j, days) -> dated[j].first to days }
            val summary = next?.let { (i, days) ->
                val label = eventLabel(resources, d.events[i])
                when (days) {
                    0L -> resources.getString(R.string.contact_page_date_today, label)
                    1L -> resources.getString(R.string.contact_page_date_tomorrow, label)
                    else -> resources.getQuantityString(R.plurals.contact_page_date_in, days.toInt(), label, days.toInt())
                }
            } ?: resources.getQuantityString(R.plurals.contact_page_count_dates, d.events.size, d.events.size)
            sections.add(ContactSection.DATES, sectionTitle(resources, ContactSection.DATES), summary) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SegmentedGroup {
                        val yearly = YearlyEvents.decode(meta?.yearlyEvents)
                        d.events.forEachIndexed { i, ev ->
                            item {
                                // A life event (new job, moved…) can be remembered yearly in the digest.
                                val date = EventDate.parse(ev.date)
                                val canYearly = date != null && d.lookupKey.isNotEmpty() && YearlyEvents.eligible(ev.type) &&
                                    !LifeEvents.isDeath(ev.type, ev.label)
                                val key = if (canYearly) YearlyEvents.key(ev.type, ev.label, date!!) else null
                                val on = key != null && key in yearly
                                fun toggle() {
                                    key ?: return
                                    page.setYearly(key, !on)
                                }
                                GroupDataRow(
                                    Icons.Rounded.Cake, i == 0, describeLifeEvent(resources, d, ev),
                                    eventLabel(resources, ev) + (if (on) resources.getString(R.string.main_separator) + resources.getString(R.string.circle_yearly_label) else ""),
                                    onClick = {},
                                    trailing = if (key == null) null else ({
                                        IconButton(::toggle) {
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
                    }
                    if (hasMissingDates(d)) MissingDateChips(vm, d, onSaved = page::reload)
                }
            }
        }
        if (d.phones.isNotEmpty()) {
            val summary = if (d.phones.size == 1) Bidi.ltr(Format.number(d.phones[0].value, vm.countryIso)) else resources.getQuantityString(R.plurals.contact_page_count_numbers, d.phones.size, d.phones.size)
            sections.add(ContactSection.PHONES, sectionTitle(resources, ContactSection.PHONES), summary) {
                SegmentedGroup {
                    d.phones.forEachIndexed { i, p ->
                        item {
                            val pinned = simPrefs.firstOrNull { PhoneIdentity.matchesStored(it.matchKey, p.value, vm.countryIso) }?.phoneAccountId
                            PhoneRow(
                                vm, p, first = i == 0,
                                label = listOfNotNull(Format.phoneType(resources, p.type, p.label), pinned?.let { id -> sims.firstOrNull { it.id == id }?.label?.let { resources.getString(R.string.detail_always_sim, it) } }).joinToString(resources.getString(R.string.main_separator)),
                                canDefault = d.phones.size > 1 && p.id != null,
                                multiSim = sims.size > 1,
                                onCall = { callPeek(p.value, d.displayName) },
                                onMessage = { message(d, p.value) },
                                onMessageOn = { messageSheet = p.value },
                                onSim = { simFor = p.value },
                                onDefault = { on -> page.setDefault(p, Phone.CONTENT_ITEM_TYPE, on) },
                            )
                        }
                    }
                }
            }
        }
        if (d.emails.isNotEmpty()) {
            val summary = if (d.emails.size == 1) d.emails[0].value else resources.getQuantityString(R.plurals.contact_page_count_emails, d.emails.size, d.emails.size)
            sections.add(ContactSection.EMAILS, sectionTitle(resources, ContactSection.EMAILS), summary) {
                SegmentedGroup {
                    d.emails.forEachIndexed { i, e ->
                        item {
                            GroupDataRow(
                                Icons.Rounded.Email, i == 0, e.value, Format.emailType(resources, e.type, e.label), onClick = { Intents.email(context, e.value) },
                                trailing = if (e.isPrimary && d.emails.size > 1) ({ Icon(Icons.Rounded.Star, stringResource(R.string.detail_default_email), tint = MaterialTheme.colorScheme.primary) }) else null,
                                menu = if (d.emails.size > 1 && e.id != null) ({ close ->
                                    DefaultMenuItem(e.isPrimary) { on -> close(); page.setDefault(e, Email.CONTENT_ITEM_TYPE, on) }
                                }) else null,
                            )
                        }
                    }
                }
            }
        }
        val mapLinks = remember(d.addresses, d.websites) { AddressMapLinks.matches(d) }
        if (d.addresses.isNotEmpty()) {
            val summary = if (d.addresses.size == 1) d.addresses[0].formatted.lines().joinToString(", ") { it.trim() } else resources.getQuantityString(R.plurals.contact_page_count_addresses, d.addresses.size, d.addresses.size)
            sections.add(ContactSection.ADDRESSES, sectionTitle(resources, ContactSection.ADDRESSES), summary) {
                SegmentedGroup {
                    d.addresses.forEachIndexed { i, a ->
                        val link = mapLinks[i]?.let { d.websites.getOrNull(it)?.value }
                        item { AddressDetailRow(a, i == 0, StructuredPostal.getTypeLabel(resources, a.type, a.label).toString(), link) }
                    }
                }
            }
        }
        val chatRows = messengers
        if (d.handles.isNotEmpty() || reachGroups.isNotEmpty()) {
            val apps = chatRows.map { it.appName }.distinct()
            val summary = if (apps.isNotEmpty()) apps.joinToString(", ") else resources.getQuantityString(R.plurals.contact_page_count_items, d.handles.size, d.handles.size)
            sections.add(ContactSection.MESSENGERS, sectionTitle(resources, ContactSection.MESSENGERS), summary) {
                Column {
                    CoachMark(Tips.REACH_USUAL, stringResource(R.string.reach_reach_hint), enabled = reachGroups.isNotEmpty())
                    SegmentedGroup {
                        // Handles typed into the contact (Matrix, Threema, Signal username…).
                        handleRows(d.handles, Icons.Rounded.Forum, onWeb = { webLink = it })
                        // "Reach via apps": each messenger's Message / Voice / Video for this person, per number.
                        reachViaAppsRows(
                            reachGroups, prefs, showNumbers = d.phones.size > 1,
                            onOpen = { row -> r.action(row)?.let { m -> ContactMessaging.startRow(context, r, m)?.let { vm.toast(it) } } },
                            onToggleUsual = { row -> savePrefs(prefs.toggleUsual(row)) },
                        )
                    }
                }
            }
        }
        // An address's map link opens from the address itself, so it isn't listed again as a website.
        val sites = d.websites.filterIndexed { i, _ -> i !in mapLinks.values }
        if (sites.isNotEmpty() || d.note.isNotBlank() || d.relations.isNotEmpty()) {
            val n = sites.size + d.relations.size + (if (d.note.isNotBlank()) 1 else 0)
            sections.add(ContactSection.ABOUT, resources.getString(R.string.detail_about, d.given.ifBlank { d.displayName }), resources.getQuantityString(R.plurals.contact_page_count_items, n, n)) {
                SegmentedGroup {
                    sites.forEachIndexed { i, w ->
                        item {
                            val label = resources.getString(R.string.detail_website)
                            GroupDataRow(Icons.Rounded.Language, i == 0, w.value, label, onClick = { Intents.web(context, w.value) })
                        }
                    }
                    d.relations.forEachIndexed { i, rel ->
                        item {
                            val label = RelationTypes.fromAndroid(rel.type, rel.label)?.let { RelationText.label(resources, it) }
                                ?: ContactsContract.CommonDataKinds.Relation.getTypeLabel(resources, rel.type, rel.label).toString()
                            GroupDataRow(Icons.Rounded.People, i == 0, rel.value, label, onClick = {
                                // By the remembered lookup key first, then by name; several namesakes: ask.
                                page.openRelation(rel.value) { target ->
                                    when (target) {
                                        is RelationTarget.Contact -> open(Routes.contact(target.id))
                                        is RelationTarget.Choose -> relationChoice = target.people
                                        is RelationTarget.None -> vm.toast(resources.getString(R.string.detail_no_contact_named, target.name))
                                    }
                                }
                            })
                        }
                    }
                    if (d.note.isNotBlank()) item {
                        GroupDataRow(Icons.AutoMirrored.Rounded.Notes, true, d.note, resources.getString(R.string.detail_note), onClick = {}, headline = { LinkifiedText(d.note) })
                    }
                }
            }
        }
        if (otherFields.isNotEmpty()) sections.add(ContactSection.OTHER, sectionTitle(resources, ContactSection.OTHER), resources.getQuantityString(R.plurals.contact_page_count_items, otherFields.size, otherFields.size)) {
            Column {
                SegmentedGroup {
                    otherFields.forEachIndexed { i, f -> item { GroupDataRow(Icons.Rounded.Info, i == 0, f.value, f.label, onClick = {}) } }
                }
                GroupNote(stringResource(R.string.detail_other_fields_note))
            }
        }
        val notes = ui.notes
        // Calls, logged interactions, call notes and dates, by month. P1: the latest few; "Show all" opens the rest.
        val timelineCount = remember(history, interactions, notes, d.events) { timelineEntries(d, history, interactions, notes, ZoneId.systemDefault()).size }
        sections.add(ContactSection.TIMELINE, sectionTitle(resources, ContactSection.TIMELINE), resources.getQuantityString(R.plurals.contact_page_entries, timelineCount, timelineCount)) {
            ContactTimeline(
                vm, d, history, interactions, notes, onEdit = { editEntry = it },
                onAllCalls = primary?.takeIf { history.size > 5 }?.let { p -> { open(Routes.history(p.value)) } },
                limit = TIMELINE_PREVIEW, onShowAll = { open(ContactPageRoutes.timeline(contactId)) }, showTitle = false,
            )
        }
        if (history.isNotEmpty()) sections.add(ContactSection.INSIGHTS, sectionTitle(resources, ContactSection.INSIGHTS), resources.getQuantityString(R.plurals.contact_page_count_calls, history.size, history.size)) {
            OnGroupSurface { CallInsightsSection(vm, d.phones.map { it.value }, showTitle = false) }
        }
        val note = meta?.pinnedNote
        sections.add(ContactSection.NOTE, sectionTitle(resources, ContactSection.NOTE), note?.lineSequence()?.firstOrNull().orEmpty().ifBlank { resources.getString(R.string.contact_page_no_note) }) {
            SegmentedGroup {
                item {
                    ListItem(
                        modifier = Modifier.clickable { editNote = true },
                        colors = groupRowColors(),
                        leadingContent = { Icon(Icons.Rounded.PushPin, null, tint = if (note != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                        headlineContent = { Text(note ?: stringResource(R.string.detail_add_note)) },
                        supportingContent = { Text(stringResource(if (note != null) R.string.detail_note_shown else R.string.detail_note_hint)) },
                    )
                }
            }
        }
        sections.add(ContactSection.SETTINGS, sectionTitle(resources, ContactSection.SETTINGS), resources.getString(R.string.contact_page_settings_summary)) {
            SegmentedGroup {
                item {
                    ListItem(
                        modifier = Modifier.toggleable(d.sendToVoicemail, role = Role.Switch, onValueChange = { v -> page.setSendToVoicemail(v) }),
                        colors = groupRowColors(),
                        leadingContent = { Icon(Icons.Rounded.Voicemail, null) },
                        headlineContent = { Text(stringResource(R.string.detail_send_to_voicemail)) },
                        trailingContent = { Switch(d.sendToVoicemail, onCheckedChange = null) },
                    )
                }
                blended { ContactCallTimeRows(vm, d.lookupKey, d.displayName, d.starred) }
                item {
                    val tone = d.customRingtone?.let { runCatching { RingtoneManager.getRingtone(context, Uri.parse(it))?.getTitle(context) }.getOrNull() }
                    GroupDataRow(Icons.Rounded.MusicNote, true, tone ?: resources.getString(R.string.detail_default_ringtone), resources.getString(R.string.detail_ringtone), onClick = {
                        ringtonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE))
                    })
                }
                item {
                    GroupDataRow(Icons.Rounded.Sync, true, d.rawContacts.joinToString("\n") { it.account.displayLabel }, if (d.rawContacts.size > 1) resources.getQuantityString(R.plurals.detail_linked_from, d.rawContacts.size, d.rawContacts.size) else resources.getString(R.string.detail_saved_in), onClick = {})
                }
                blended { ProvenanceRow(vm, contactId, d, open) }
                blended { CallBackgroundInfoRow(vm, d) }
            }
        }
        val shown = sections.shown(layout)

        Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "header") {
                Column(
                    Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }.padding(horizontal = 16.dp).padding(top = 8.dp),
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
                        Avatar(
                            d.displayName, d.photoUri, heroSize,
                            Modifier.shared("avatar-$contactId").clickable(enabled = d.photoUri != null, onClickLabel = stringResource(R.string.detail_view_photo)) { showPhoto = true },
                            isCompany = d.composedName.isBlank() && d.company.isNotBlank(),
                        )
                        Text(d.displayName, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp).shared("name-$contactId", bounds = true))
                    }
                    val sub = listOf(d.nickname, listOf(d.title, d.company).filter { it.isNotBlank() }.joinToString(", ")).filter { it.isNotBlank() }
                    if (sub.isNotEmpty()) Text(sub.joinToString(stringResource(R.string.main_separator)), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Text(lastTalked, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    temp?.let { Text(stringResource(R.string.detail_deletes_on, Format.fullDate(context, it.expiresAt)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    AccountChips(vm, d, open) { newId ->
                        if (newId != null && newId != contactId) { back(); open(Routes.contact(newId)) }
                        else page.reload()
                    }
                    Spacer(Modifier.height(16.dp))
                    // Labelled tiles.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionTile(Icons.Rounded.Call, if (preferredCall != null) preferredCall.appName else stringResource(R.string.main_call), canCall) { doCall() }
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
            // Every section folds; order, start modes and hidden ones come from Settings › Contacts › Contact page sections.
            foldableSections(sections, layout, ::fold)
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
            val jumps = if (peopleSettings.sectionChips && shown.size >= JUMP_CHIPS_FROM) shown.map { s ->
                sections.titleOf(s) to {
                    if (layout.isFolded(s)) fold(s, false)
                    scope.launch {
                        listState.animateScrollToItem(1 + shown.indexOf(s))
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
        if (showQr) QrDialog(d) { showQr = false }
        if (secureQr) SecureQrDialog(d) { secureQr = false }
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
        peekNumber?.let { n ->
            PreCallPeekSheet(
                vm, d.lookupKey, d.given.ifBlank { d.displayName }, memory, goodTime,
                onCall = { peekNumber = null; vm.requestCall(n, d.displayName) },
                onDismiss = { peekNumber = null },
            )
        }
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
                            ListItem(headlineContent = { Text(stringResource(R.string.main_call_who, Bidi.ltr(Format.number(p.value, vm.countryIso)))) }, leadingContent = { Icon(Icons.Rounded.Call, null) }, modifier = Modifier.clickable {
                                pinDialog = false
                                Shortcuts.pin(context, Shortcuts.Kind.CALL, d.displayName, p.value, contactId, d.photoUri)
                            })
                            ListItem(headlineContent = { Text(stringResource(R.string.main_message_who, Bidi.ltr(Format.number(p.value, vm.countryIso)))) }, leadingContent = { Icon(Icons.AutoMirrored.Rounded.Message, null) }, modifier = Modifier.clickable {
                                pinDialog = false
                                Shortcuts.pin(context, Shortcuts.Kind.MESSAGE, d.displayName, p.value, contactId, d.photoUri)
                            })
                        }
                        ListItem(headlineContent = { Text(stringResource(R.string.main_open_contact)) }, leadingContent = { Icon(Icons.Rounded.Person, null) }, modifier = Modifier.clickable {
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
                            ListItem(
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
        d.photoUri?.takeIf { showPhoto }?.let { PhotoViewer(it) { showPhoto = false } }
        if (confirmDelete) {
            ConfirmDialog(
                title = stringResource(R.string.detail_delete_title, d.displayName),
                text = stringResource(R.string.detail_delete_body),
                confirmLabel = stringResource(R.string.main_delete),
                onConfirm = { confirmDelete = false; vm.deleteContacts(listOf(contactId)); back() },
                onDismiss = { confirmDelete = false },
                destructive = true,
                dismissLabel = stringResource(R.string.main_cancel),
            )
        }
        simFor?.let { number ->
            ParleyDialog(
                onDismissRequest = { simFor = null },
                title = { Text(stringResource(R.string.detail_sim_for, Bidi.ltr(Format.number(number, vm.countryIso)))) },
                text = {
                    Column {
                        ListItem(headlineContent = { Text(stringResource(R.string.detail_sim_ask)) }, modifier = Modifier.clickable { page.setSimFor(number, null); simFor = null })
                        sims.forEach { s ->
                            ListItem(headlineContent = { Text(stringResource(R.string.detail_always_sim, s.label)) }, leadingContent = { Icon(Icons.Rounded.SimCard, null) }, modifier = Modifier.clickable {
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

/** Timeline entries shown on the page before "Show all". */
private const val TIMELINE_PREVIEW = 5

/** Jump chips appear from this many shown sections. */
private const val JUMP_CHIPS_FROM = 4

/**
 * The contact page's header photo: 168 dp for a real photo and 136 dp for a monogram on a phone held upright, more
 * on tablets, and 104 / 96 dp when the screen is short (a phone in landscape), so the name and the action tiles still
 * fit under it. The sizes are in dp, so large fonts don't crowd the photo.
 */
@Composable
private fun heroPhotoSize(hasPhoto: Boolean): Dp {
    val conf = LocalConfiguration.current
    return when {
        conf.screenHeightDp < 480 -> if (hasPhoto) 104.dp else 96.dp
        conf.screenWidthDp >= 600 && conf.screenHeightDp >= 700 -> if (hasPhoto) 192.dp else 152.dp
        else -> if (hasPhoto) 168.dp else 136.dp
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

/** One number: tap calls; the chat icon messages it; long-press: copy, default, message on…, SIM. */
@Composable
private fun PhoneRow(
    vm: AppViewModel,
    p: DataItem,
    first: Boolean,
    label: String,
    canDefault: Boolean,
    multiSim: Boolean,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onMessageOn: () -> Unit,
    onSim: () -> Unit,
    onDefault: (Boolean) -> Unit,
) {
    GroupDataRow(
        Icons.Rounded.Call, first, p.value, label, onClick = onCall,
        headline = { Text(Bidi.ltr(Format.number(p.value, vm.countryIso))) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.isPrimary && canDefault) Icon(Icons.Rounded.Star, stringResource(R.string.detail_default_number), tint = MaterialTheme.colorScheme.primary)
                if (multiSim) IconButton(onSim) { Icon(Icons.Rounded.SimCard, stringResource(R.string.detail_choose_sim_number)) }
                IconButton(onMessage) { Icon(Icons.AutoMirrored.Rounded.Chat, stringResource(R.string.detail_message_number)) }
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

@Composable
fun Section(title: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = Spacing.s), color = MaterialTheme.colorScheme.surfaceContainerHigh)
        ListSectionHeader(title, top = Spacing.m)
    }
}
