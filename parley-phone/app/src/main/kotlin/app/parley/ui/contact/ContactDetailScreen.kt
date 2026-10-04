package app.parley.ui.contact

import android.app.Activity
import android.content.res.Resources
import android.media.RingtoneManager
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.EventDate
import app.parley.common.people.ContactGlance
import app.parley.common.people.ContactRef
import app.parley.common.people.GlanceFact
import app.parley.common.people.LifeEvents
import app.parley.common.people.PageBlock
import app.parley.common.people.SectionFamily
import app.parley.common.photo.OriginalPhoto
import app.parley.data.ContactDetails
import app.parley.data.PhoneEnv
import app.parley.data.primary
import app.parley.data.people.OriginalPhotos
import app.parley.ui.Avatar
import app.parley.ui.BackButton
import app.parley.ui.Destination
import app.parley.ui.ParleyMotion
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyTopBar
import app.parley.ui.Routes
import app.parley.ui.ScreenSnackbarHost
import app.parley.ui.Spacing
import app.parley.ui.blocking.rememberBlocked
import app.parley.ui.blocking.rememberEmergency
import app.parley.ui.circle.goodTimeText
import app.parley.ui.circle.hasPeek
import app.parley.ui.common.Intents
import app.parley.ui.menus.MenuShortcutsBlock
import app.parley.ui.cases.CaseCard
import app.parley.ui.cases.CaseOwner
import app.parley.ui.cases.rememberCaseShown
import app.parley.ui.people.LockPrivateRow
import app.parley.ui.people.cards.CardUpdateBanner
import app.parley.ui.screenViewModel
import java.time.LocalDate
import kotlinx.coroutines.launch

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
 *
 * The parts are in their own files: [ContactHeader], [ContactInfoSections], [AboutSections], [HistorySections],
 * [ContactSettingsSection], the top bar's [ContactBarActions] and [ContactDialogHost], which draws the one
 * [ContactDialog] open at a time. [inPane]: drawn beside the contact list on a big screen (Home's list-detail
 * layout), where there is no back arrow and Home's own screen shows the snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(vm: AppViewModel, contactId: Long, back: () -> Unit, open: (Destination) -> Unit, inPane: Boolean = false) {
    val page: ContactDetailViewModel = screenViewModel()
    LaunchedEffect(contactId) { page.start(contactId) }
    LaunchedEffect(page) { page.events.collect { vm.toast(it) } }
    val ui by page.state.collectAsStateWithLifecycle()
    // The one dialog, sheet or menu open. The plain ones come back after a rotation (so a "Save to" a photo or QR
    // viewer opened still writes its file); on a private contact's page, none that holds a number or name.
    val dialogSaver = if (ContactRef.ofNavId(contactId) is ContactRef.Private) ContactDialog.PrivateSaver else ContactDialog.Saver
    var dialog by rememberSaveable(stateSaver = dialogSaver) { mutableStateOf<ContactDialog>(ContactDialog.None) }
    val ctx = pageContext(vm, page, ui, contactId, open, back) { next -> dialog = next }
    val d = ctx?.d
    val listState = rememberLazyListState()
    // The bar takes the scrolled-content tint once content passes under it.
    val barColor by animateColorAsState(
        if (listState.canScrollBackward) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface, label = "bar",
    )
    // The header photo: a real photo gets the most room, a monogram less, and a landscape phone keeps it small
    // enough to leave the actions in view. Photo, name, the at-a-glance line and the tiles fit in about a third
    // of an upright phone's screen.
    val heroSize = heroPhotoSize(d?.photoUri != null)
    // The photo as picked (whole, in its own shape) when Parley kept it; a tall one makes the header taller.
    val original = rememberOriginalPhoto(vm, d?.lookupKey, d?.photoUri)
    val heroHeight = heroSize * (original?.let { OriginalPhoto.hero(it.width, it.height).height } ?: 1f)
    // The header has scrolled away once the name is under the top bar.
    val collapseAt = with(LocalDensity.current) { (heroHeight + 56.dp).toPx() }
    val collapsed by remember(collapseAt) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > collapseAt } }
    val numbers = remember(d?.phones) { d?.phones?.map { it.value }.orEmpty() }
    val numbersBlocked = rememberBlocked(vm, numbers)
    val onlyEmergency = rememberEmergency(vm, numbers)
    ParleyScaffold(
        topBar = {
            val actions: @Composable () -> Unit = { if (ctx != null) ContactBarActions(ctx, dialog, numbersBlocked, onlyEmergency) }
            ContactTopBar(ctx, collapsed, barColor, back.takeUnless { inPane }, actions)
        },
        // Beside the list, Home's screen already shows the app's snackbar.
        snackbarHost = { if (!inPane) ScreenSnackbarHost() },
        floatingActionButton = {
            if (ctx != null && ctx.inCircle) {
                ExtendedFloatingActionButton(
                    onClick = { dialog = ContactDialog.LogInteraction },
                    icon = { Icon(Icons.Rounded.Handshake, null) },
                    text = { Text(stringResource(R.string.circle_log_interaction)) },
                )
            }
        },
    ) { padding ->
        if (ctx == null) {
            if (ui.loaded) Text(stringResource(R.string.detail_gone), Modifier.padding(padding).padding(24.dp))
            return@ParleyScaffold
        }
        ContactPageBody(ctx, padding, listState, barColor, original, heroSize, collapseAt)
        ContactDialogHost(ctx, dialog, original)
    }
}

/**
 * The page's top bar: once the header has scrolled under it ([docked]), the small photo, name and "last talked" are
 * its title. No [back] beside the list on a big screen, where there is nothing to go back to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactTopBar(ctx: ContactPageContext?, docked: Boolean, barColor: Color, back: (() -> Unit)?, actions: @Composable () -> Unit) {
    ParleyTopBar(
        title = { AnimatedVisibility(docked && ctx != null, enter = fadeIn(), exit = fadeOut()) { ctx?.let { DockedTitle(it) } } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = barColor, scrolledContainerColor = barColor),
        navigationIcon = { back?.let { BackButton(it) } },
        actions = { actions() },
    )
}

/** What the page's parts share, for the contact as loaded; null while it isn't (or is gone). */
@Composable
private fun pageContext(
    vm: AppViewModel,
    page: ContactDetailViewModel,
    ui: ContactDetailUiState,
    contactId: Long,
    open: (Destination) -> Unit,
    back: () -> Unit,
    show: (ContactDialog) -> Unit,
): ContactPageContext? {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val circleCfg by page.circleConfig.collectAsStateWithLifecycle()
    val relationsFromOthers by page.relationsFromOthers.collectAsStateWithLifecycle()
    val parleyRelations by page.parleyRelations.collectAsStateWithLifecycle()
    // Registered before the contact has loaded, so a ringtone picked meanwhile still comes back.
    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            page.setRingtone(uri)
        }
    }
    val today = remember { LocalDate.now() }
    val history = ui.history
    val talked = history.firstOrNull { it.durationSec > 0 }
    val lastTalked = if (talked != null) {
        stringResource(R.string.detail_last_talked, DateUtils.getRelativeTimeSpanString(talked.date, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS))
    } else {
        stringResource(R.string.recents_empty)
    }
    val d = ui.details
    val goodTime = remember(history, d?.phones) { goodTimeText(resources, history, d?.phones?.primary()?.value, PhoneEnv.countryIso(context)) }
    // Dates that come round again (a date of death doesn't), with their place in d.events; a date kept by another
    // calendar counts from its next Gregorian day.
    val dated = remember(d?.events) {
        d?.events.orEmpty().mapIndexedNotNull { i, ev ->
            EventDate.parse(ev.date)?.takeUnless { LifeEvents.isDeath(ev.type, ev.label) }?.let { effectiveDate(it, ev.calendar, today) }?.let { i to it }
        }
    }
    // Case files: an organisation's calls and reference numbers, on the page and before calling.
    val caseOwner = remember(d?.displayName, d?.phones, ui.isPrivate) { d?.let { caseOwnerOf(it, ui.isPrivate) } ?: CaseOwner("", emptyList()) }
    val case = rememberCaseShown(vm, caseOwner)
    if (d == null) return null
    return ContactPageContext(
        vm, page, ui, d, contactId, context, scope, resources, goodTime, lastTalked, today, dated, parleyRelations, relationsFromOthers,
        open = open, back = back, show = show,
        callPeek = { number, name ->
            if (circleCfg.preCallPeek && (hasPeek(ui.memory, goodTime) || case.shown)) show(ContactDialog.Peek(number)) else vm.requestCall(number, name)
        },
        openRelation = { name ->
            page.openRelation(name) { target ->
                when (target) {
                    is RelationTarget.Contact -> open(Routes.contact(target.id))
                    is RelationTarget.Choose -> show(ContactDialog.ChooseRelation(target.people))
                    is RelationTarget.None -> vm.toast(resources.getString(R.string.detail_no_contact_named, target.name))
                }
            }
        },
        pickRingtone = { ringtonePicker.launch(it) },
        case = case,
    )
}

/** The small photo, name and "last talked" that take the top bar's title once the header has scrolled under it. */
@Composable
private fun DockedTitle(ctx: ContactPageContext) {
    val d = ctx.d
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(d.displayName, d.photoUri, 36.dp, isCompany = d.composedName.isBlank() && d.company.isNotBlank())
        Column(Modifier.padding(start = 12.dp)) {
            Text(d.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ctx.lastTalked, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/**
 * The page under the top bar: the header, then every section folding in the order chosen in Settings; once the big
 * header has gone, a compact bar keeps the actions (and, on long pages, jumps to a section).
 */
@Suppress("LongParameterList") // The scaffold's measures and the header's scroll state, shared with the top bar.
@Composable
private fun ContactPageBody(
    ctx: ContactPageContext,
    padding: PaddingValues,
    listState: LazyListState,
    barColor: Color,
    original: OriginalPhotos.Original?,
    heroSize: Dp,
    collapseAt: Float,
) {
    val vm = ctx.vm
    val ui = ctx.ui
    val d = ctx.d
    val resources = LocalResources.current
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
    val sections = PageSections()
    StayInTouchSection(sections, ctx)
    ContactInfoSections(sections, ctx)
    AboutSections(sections, ctx)
    HistorySections(sections, ctx)
    ContactSettingsSection(sections, ctx)
    val locked = ui.access == PrivateAccess.LOCKED || ui.access == PrivateAccess.UNAVAILABLE
    val blocks = if (locked) emptyList() else sections.blocks(layout)
    fun blockTitle(b: PageBlock): String = blockTitle(resources, sections, d, b)
    val glance = glanceText(ctx)
    val unlock = ctx::unlock
    // The compact action bar is pinned once the big tiles have scrolled under the top bar.
    var headerHeight by remember { mutableIntStateOf(0) }
    val pinAt = with(LocalDensity.current) { 48.dp.toPx() }
    val pinned by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || (headerHeight > 0 && listState.firstVisibleItemScrollOffset > headerHeight - pinAt) }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            item(key = "header") {
                ContactHeader(
                    ctx, original, heroSize, glance, locked, unlock, collapse = { headerFraction },
                    modifier = Modifier.onSizeChanged { headerHeight = it.height },
                )
            }
            pageNotices(ctx, locked, unlock)
            // Every section folds; order, start modes and hidden ones come from Settings › Contacts › Contact page sections.
            if (!locked) foldableSections(sections, layout, ::blockTitle, ::fold)
        }
        AnimatedVisibility(
            pinned, Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
            enter = expandVertically(ParleyMotion.spatial(), expandFrom = Alignment.Top) + fadeIn(ParleyMotion.effects()),
            exit = shrinkVertically(ParleyMotion.fastSpatial(), shrinkTowards = Alignment.Top) + fadeOut(ParleyMotion.fastEffects()),
        ) {
            val jumps = if (peopleSettings.sectionChips && blocks.size >= JUMP_CHIPS_FROM) {
                blocks.map { b -> blockTitle(b) to { if (layout.isFolded(b)) fold(b, false) } }
            } else {
                emptyList()
            }
            PinnedBar(ctx, listState, barColor, jumps)
        }
    }
}

/** A group's title: its family's ("Contact info", "About Ana") or, alone, its section's. */
private fun blockTitle(res: Resources, sections: PageSections, d: ContactDetails, b: PageBlock): String = when (b.family) {
    SectionFamily.CONTACT_INFO -> res.getString(R.string.contact_page_info)
    SectionFamily.ABOUT -> res.getString(R.string.detail_about, d.given.ifBlank { d.displayName })
    null -> sections.titleOf(b.sections.first())
}

/** Between the header and the sections: a card update to review, menu shortcuts, and a private contact's locked state. */
private fun LazyListScope.pageNotices(ctx: ContactPageContext, locked: Boolean, unlock: () -> Unit) {
    val vm = ctx.vm
    val ui = ctx.ui
    val d = ctx.d
    // I14: a newer signed card from this person, waiting for review (never applied by itself).
    if (ui.access == PrivateAccess.OPEN && d.lookupKey.isNotEmpty()) item(key = "card_update") { CardUpdateBanner(vm, ctx.contactId, d.lookupKey, d) }
    // Case files: an organisation's calls, hold times and reference numbers (also in the pre-call peek).
    if (!locked && ctx.case.shown) item(key = "case_file") { CaseCard(vm, ctx.caseOwner, ctx.open) }
    // I6: menu shortcuts saved for this person's numbers (from the call screen's keypad).
    if (!locked && d.phones.isNotEmpty()) item(key = "menu_shortcuts") { MenuShortcutsBlock(vm, d.phones.map { it.value }, d.displayName, d.photoUri) }
    // "Calls to Ana drop less on SIM 2": on a dual-SIM phone, until answered either way.
    if (!locked && d.phones.isNotEmpty()) item(key = "sim_advice") { SimAdviceBanner(ctx) }
    // A private contact while the vault is locked: its name, photo and numbers only, and the unlock right here.
    if (ui.access != PrivateAccess.OPEN && ui.access != PrivateAccess.OPENING) {
        item(key = "access") { PrivateAccessRow(ui.access, onUnlock = unlock, onRetry = ctx.page::reload, onKeep = ctx.page::keepWhatIsLeft) }
    }
    // Unlocked: one tap locks every private contact again (this page then shows its locked state).
    if (ui.isPrivate && ui.access == PrivateAccess.OPEN) item(key = "private_lock") { LockPrivateRow(vm) }
}

/** At a glance under the name: last talked, the next date when it's close, open promises. */
@Composable
private fun glanceText(ctx: ContactPageContext): String {
    val talkedAt = ctx.ui.history.firstOrNull { it.durationSec > 0 }?.date
    val resources = LocalResources.current
    val promises = ctx.ui.memory.promises.size
    val glance = remember(talkedAt, ctx.dated, promises, ctx.today) { ContactGlance.facts(talkedAt, ctx.dated.map { it.second }, ctx.today, promises) }
    return glance.joinToString(stringResource(R.string.main_separator)) { f ->
        when (f) {
            is GlanceFact.LastTalked, GlanceFact.NoCalls -> ctx.lastTalked
            is GlanceFact.NextDate -> ctx.dateText(ctx.dated[f.index].first, f.days)
            is GlanceFact.OpenPromises -> resources.getQuantityString(R.plurals.contact_page_open_promises, f.count, f.count)
        }
    }
}

/**
 * The compact bar under the top bar once the header has gone: only the ways to reach them, as on the big tiles (star,
 * edit and ⋮ stay in the top bar above, so no action shows twice), and on long pages a chip per group that unfolds
 * it ([unfold]) and scrolls to it.
 */
@Composable
private fun PinnedBar(ctx: ContactPageContext, listState: LazyListState, barColor: Color, unfold: List<Pair<String, () -> Unit>>) {
    val d = ctx.d
    val context = LocalContext.current
    var pinnedHeight by remember { mutableIntStateOf(0) }
    val email = ctx.email
    val actions = listOfNotNull(
        QuickAction(Icons.Rounded.Call, stringResource(R.string.main_call_who, d.displayName), ctx.canCall) { ctx.call() },
        QuickAction(Icons.AutoMirrored.Rounded.Message, stringResource(R.string.main_message_who, d.displayName), ctx.canMessage) { ctx.message() },
        if (ctx.reach.videoRows.isNotEmpty()) {
            QuickAction(Icons.Rounded.Videocam, ctx.preferredVideo?.appName ?: stringResource(R.string.detail_video), true) { ctx.video() }
        } else {
            null
        },
        if (email != null) QuickAction(Icons.Rounded.Email, stringResource(R.string.detail_email), true) { Intents.email(context, email.value) } else null,
    )
    val jumps = unfold.mapIndexed { i, (title, open) ->
        title to {
            open()
            ctx.scope.launch {
                listState.animateScrollToItem(1 + i)
                listState.animateScrollBy(-pinnedHeight.toFloat())
            }
            Unit
        }
    }
    Surface(color = barColor, modifier = Modifier.onSizeChanged { pinnedHeight = it.height }) {
        PinnedContactBar(actions, jumps)
    }
}

/** Jump chips appear from this many shown groups. */
private const val JUMP_CHIPS_FROM = 4
