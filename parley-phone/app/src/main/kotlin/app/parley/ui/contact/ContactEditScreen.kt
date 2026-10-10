package app.parley.ui.contact

import app.parley.common.catching
import app.parley.ui.Spacing
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import app.parley.common.people.ContactRef
import androidx.compose.ui.platform.LocalContext
import app.parley.security.AppLock
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import app.parley.common.photo.OriginalPhoto
import android.text.format.Formatter
import android.content.res.Resources
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.R
import app.parley.common.people.EditorForm
import app.parley.common.people.NativeName
import app.parley.messaging.CountryPickerDialog
import app.parley.common.people.PhoneTypes
import app.parley.data.CustomFieldItem
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Abc
import app.parley.common.people.HandleService
import app.parley.common.people.Handles
import app.parley.common.people.LifeEvents
import app.parley.common.people.RelationLinks
import app.parley.common.people.RelationTypes
import app.parley.common.people.RowKeys
import app.parley.common.people.RowOrder
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.EventItem
import app.parley.data.HandleItem
import app.parley.data.NumberInfo
import app.parley.data.PostalItem
import app.parley.data.GroupInfo
import app.parley.ui.people.BackgroundChange
import app.parley.ui.Routes
import app.parley.ui.people.CallBackgroundEditor
import app.parley.ui.people.DuplicateWarning
import app.parley.ui.people.HandleText
import app.parley.ui.people.RelationText
import app.parley.ui.people.eventLabel
import app.parley.ui.screenViewModel
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyDialog
import app.parley.ui.ConfirmDialog
import app.parley.ui.ParleyShapes
import app.parley.ui.animatedCorners
import app.parley.ui.ParleyMotion
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import app.parley.ui.FieldSide
import app.parley.ui.FormRow
import app.parley.ui.FormTokens
import app.parley.common.people.SocialProfiles
import app.parley.common.people.Profile
import androidx.compose.material.icons.rounded.AlternateEmail
import app.parley.ui.ParleyFormField
import app.parley.ui.formFieldShape

// The six common phone types; Android's other fourteen are behind "More types…" (PhoneTypes).
private val phoneTypes = PhoneTypes.common
private val emailTypes = listOf(Email.TYPE_HOME, Email.TYPE_WORK, Email.TYPE_MOBILE, Email.TYPE_OTHER)
private val postalTypes = listOf(StructuredPostal.TYPE_HOME, StructuredPostal.TYPE_WORK, StructuredPostal.TYPE_OTHER)
private val webTypes = listOf(Website.TYPE_HOMEPAGE, Website.TYPE_WORK, Website.TYPE_OTHER)
private val eventTypes = listOf(Event.TYPE_BIRTHDAY, Event.TYPE_ANNIVERSARY, Event.TYPE_OTHER)

// Focus keys of the fixed fields (row keys from RowKeys are positive).
private const val KEY_FIRST = -1L
private const val KEY_NICK = -2L
private const val KEY_NOTE = -3L
private const val KEY_COMPANY = -4L
private const val KEY_CONTEXT = -5L
private const val KEY_LANGUAGE = -6L
private const val KEY_NATIVE = -7L

/** Phones, e-mails and websites share one row layout; this says how each differs. */
private class MultiKind(
    val group: String,
    val icon: ImageVector,
    val title: Int,
    val add: Int,
    val remove: Int,
    val types: List<Int>,
    val newType: Int,
    val keyboard: KeyboardType,
    val typeLabel: (Resources, Int) -> String,
    val get: (ContactDetails) -> List<DataItem>,
    val set: (ContactDetails, List<DataItem>) -> ContactDetails,
    val looksWrong: ((String) -> Boolean)? = null,
    val hint: Int = 0,
)

private val PHONES = MultiKind(
    "phone", Icons.Rounded.Phone, R.string.detail_phone, R.string.edit_add_phone, R.string.editor_remove_phone, phoneTypes, Phone.TYPE_MOBILE,
    KeyboardType.Phone, { r, t -> Phone.getTypeLabel(r, t, null).toString() }, { it.phones }, { d, l -> d.copy(phones = l) },
    EditorForm::phoneLooksWrong, R.string.editor_phone_hint,
)
private val EMAILS = MultiKind(
    "email", Icons.Rounded.Email, R.string.detail_email, R.string.edit_add_email, R.string.editor_remove_email, emailTypes, Email.TYPE_HOME,
    KeyboardType.Email, { r, t -> Email.getTypeLabel(r, t, null).toString() }, { it.emails }, { d, l -> d.copy(emails = l) },
    EditorForm::emailLooksWrong, R.string.editor_email_hint,
)
private val WEBSITES = MultiKind(
    "web", Icons.Rounded.Language, R.string.detail_website, R.string.edit_add_website, R.string.editor_remove_website, webTypes, Website.TYPE_HOMEPAGE, KeyboardType.Uri,
    { r, t -> r.getString(when (t) { Website.TYPE_HOMEPAGE -> R.string.edit_web_homepage; Website.TYPE_WORK -> R.string.edit_web_work; else -> R.string.edit_web_other }) },
    { it.websites }, { d, l -> d.copy(websites = l) },
)

private const val G_ADDR = "addr"
private const val G_DATE = "date"
private const val G_HANDLE = "handle"
private const val G_REL = "rel"
private const val G_PARLEY_REL = "parleyRel"
private const val G_CUSTOM = "custom"

/** What a form line shows in the start gutter: the group's icon and title, on the group's first line only. */
private class Lead(val icon: ImageVector?, val title: String?) {
    companion object {
        val None = Lead(null, null)
    }
}

/** What opened the editor (the screen's arguments the form's parts read). */
private class EditorScreenArgs(
    val contactId: Long?,
    val vaultId: Long?,
    val rawId: Long?,
    val meCard: Boolean,
    val pasteText: String?,
)

/**
 * What the form's parts share: the editor, focus (a requester per row key, the field to focus next, where each key sits
 * in the list), and the pickers a row can open. The edit itself lives in [EditorViewModel].
 */
@Stable
private class EditorFormUi(
    val editor: EditorViewModel,
    focusKey: MutableState<Long?>,
    pickDateFor: MutableState<Long?>,
    mapLinkFor: MutableState<Int?>,
    pickProfile: MutableState<Boolean>,
    pickCountry: MutableState<Boolean>,
) {
    val requesters = HashMap<Long, FocusRequester>()

    /** Which website rows are profiles, decided once per row. */
    val profileRows = HashMap<Long, Boolean>()

    /** Where each row key sits in the list, to scroll to a new row. */
    val keyIndex = HashMap<Any, Int>()
    var focusKey by focusKey
    var pickDateFor by pickDateFor

    /** The address whose "Add from map link" dialog is open. */
    var mapLinkFor by mapLinkFor

    /** "Add a profile": the service list. */
    var pickProfile by pickProfile

    /** "Add a country" for citizenship: the country picker. */
    var pickCountry by pickCountry

    val keys: RowKeys get() = editor.keys

    fun fr(key: Long): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun update(f: (ContactDetails) -> ContactDetails) = editor.update(f)

    /** Appends a row to a group, remembers its key and moves the focus there. */
    fun addRow(group: String, size: Int, change: (ContactDetails) -> ContactDetails): Long {
        val k = keys.added(group, size)
        update(change)
        focusKey = k
        return k
    }

    fun removeRow(group: String, i: Int, change: (ContactDetails) -> ContactDetails) {
        keys.removed(group, i)
        update(change)
    }

    /** Swaps rows [a] and [b] of [group] (Move up / Move down): their keys go along, so focus and animations follow. */
    fun <T> swapRows(group: String, a: Int, b: Int, get: (ContactDetails) -> List<T>, set: (ContactDetails, List<T>) -> ContactDetails) {
        keys.swapped(group, a, b)
        update { set(it, RowOrder.swap(get(it), a, b)) }
    }

    /** Rows another app marks read-only can't be written again, so their group keeps the provider's order. */
    fun movable(ids: List<Long?>): Boolean = RowOrder.canReorder(ids, editor.original?.readOnlyDataIds.orEmpty())

    @Suppress("CyclomaticComplexMethod") // One branch per kind.
    fun addKind(k: EditorForm.Kind, d: ContactDetails) {
        editor.revealed = editor.revealed + k
        val cur = editor.draft ?: d
        when (k) {
            EditorForm.Kind.NAME_DETAILS -> { editor.moreName = true; focusKey = KEY_NICK }
            EditorForm.Kind.PHONE -> addRow(PHONES.group, cur.phones.size) { it.copy(phones = it.phones + DataItem(type = PHONES.newType)) }
            EditorForm.Kind.EMAIL -> addRow(EMAILS.group, cur.emails.size) { it.copy(emails = it.emails + DataItem(type = EMAILS.newType)) }
            EditorForm.Kind.WORK -> focusKey = KEY_COMPANY
            EditorForm.Kind.DATE -> pickDateFor = addRow(G_DATE, cur.events.size) { it.copy(events = it.events + EventItem(type = Event.TYPE_BIRTHDAY)) }
            EditorForm.Kind.ADDRESS -> addRow(G_ADDR, cur.addresses.size) { it.copy(addresses = it.addresses + PostalItem(type = StructuredPostal.TYPE_HOME)) }
            EditorForm.Kind.WEBSITE -> addRow(WEBSITES.group, cur.websites.size) { it.copy(websites = it.websites + DataItem(type = Website.TYPE_HOMEPAGE)) }
            EditorForm.Kind.PROFILE -> pickProfile = true
            EditorForm.Kind.HANDLE -> addRow(G_HANDLE, cur.handles.size) { it.copy(handles = it.handles + HandleItem()) }
            EditorForm.Kind.RELATION -> addRow(G_REL, cur.relations.size) { it.copy(relations = it.relations + DataItem(type = Relation.TYPE_SPOUSE)) }
            EditorForm.Kind.NOTE -> focusKey = KEY_NOTE
            EditorForm.Kind.WHEN_THEY_CALL -> focusKey = KEY_CONTEXT
            EditorForm.Kind.CUSTOM_FIELD -> addRow(G_CUSTOM, cur.customFields.size) { it.copy(customFields = it.customFields + CustomFieldItem()) }
            EditorForm.Kind.LANGUAGE -> focusKey = KEY_LANGUAGE
            EditorForm.Kind.NATIVE_NAME -> focusKey = KEY_NATIVE
            EditorForm.Kind.CITIZENSHIP -> pickCountry = true
            EditorForm.Kind.LABELS, EditorForm.Kind.CALL_BACKGROUND -> Unit
        }
    }
}

@Composable
private fun rememberEditorFormUi(editor: EditorViewModel): EditorFormUi {
    val focusKey = remember { mutableStateOf<Long?>(null) }
    val pickDateFor = remember { mutableStateOf<Long?>(null) }
    val mapLinkFor = rememberSaveable { mutableStateOf<Int?>(null) }
    val pickProfile = rememberSaveable { mutableStateOf(false) }
    val pickCountry = rememberSaveable { mutableStateOf(false) }
    return remember(editor) { EditorFormUi(editor, focusKey, pickDateFor, mapLinkFor, pickProfile, pickCountry) }
}

/**
 * The contact editor: where it's saved, the photo and the name, then only the groups the contact holds, and one "Add"
 * control for the rest. Its parts are the top bar ([EditorTopBar]), the form ([EditorBody]: [EditorHeader] and
 * [editorFields]), the pickers rows open ([EditorPickers]) and the questions it asks ([EditorDialogs]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditScreen(
    vm: AppViewModel,
    contactId: Long?,
    prefillName: String,
    prefillPhone: String,
    prefillEmail: String,
    addPhone: String,
    prefill: ContactDetails? = null,
    /** Private vault mode: 0 = new vault contact, > 0 = edit that vault contact. */
    vaultId: Long? = null,
    /** Edit one specific copy (raw contact) of the contact ("Edit this copy" on the contact page). */
    rawId: Long? = null,
    /** "My card": your own details with the same form, plus what its QR code and vCard include. */
    meCard: Boolean = false,
    /** Text shared to Parley ("Make a contact from this text"): its details are shown to tick at once. */
    pasteText: String? = null,
    done: (Long?) -> Unit,
) {
    // The edit lives in the screen's view model (and its saved state), so rotation, a theme, font or language change
    // and process death keep it; this composable only draws it.
    val editor: EditorViewModel = screenViewModel()
    LaunchedEffect(Unit) { editor.start(EditorArgs(contactId, prefillName, prefillPhone, prefillEmail, addPhone, prefill, vaultId, rawId, meCard)) }
    EditorEvents(vm, editor, done)
    val args = EditorScreenArgs(contactId, vaultId, rawId, meCard, pasteText)
    val form = rememberEditorFormUi(editor)
    var confirmDiscard by remember { mutableStateOf(false) }

    // Unsaved-changes guard with predictive back: the editor shrinks with the gesture, then asks.
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = editor.changed && !editor.saving && !confirmDiscard && editor.askKeep == null && editor.conflict == null) { events ->
        try {
            events.collect { backProgress = it.progress }
            confirmDiscard = true
        } finally {
            backProgress = 0f
        }
    }
    val shrink by animateFloatAsState(backProgress, ParleyMotion.spatial(), label = "back")

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    ParleyScaffold(
        modifier = Modifier
            .graphicsLayer {
                val s = 1f - 0.08f * shrink
                scaleX = s
                scaleY = s
                shape = animatedCorners((32 * shrink).dp)
                clip = shrink > 0f
            }
            .nestedScroll(scroll.nestedScrollConnection),
        topBar = { EditorTopBar(editor, args, scroll, onClose = { if (editor.changed) confirmDiscard = true else done(null) }) },
    ) { padding ->
        val d = editor.draft
        if (d == null) {
            val desc = stringResource(R.string.editor_loading)
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = desc })
            }
            return@ParleyScaffold
        }
        EditorBody(vm, editor, form, d, args, padding, done)
    }
    EditorDialogs(editor, confirmDiscard, { confirmDiscard = it }, done)
}

/** What the editor's view model says: messages, the end of the edit, an Undo, and a private contact's unlock. */
@Composable
private fun EditorEvents(vm: AppViewModel, editor: EditorViewModel, done: (Long?) -> Unit) {
    val latestDone by rememberUpdatedState(done)
    val activity = LocalActivity.current as? ComponentActivity
    LaunchedEffect(editor) {
        editor.events.collect { e ->
            when (e) {
                is EditorEvent.Message -> vm.toast(e.text)
                is EditorEvent.Done -> latestDone(e.savedId)
                is EditorEvent.Mirrored -> e.undo?.let { vm.offerUndo(e.text, it) } ?: vm.toast(e.text)
                // Private contacts are locked: their unlock, then the open or the save goes on with every edit kept.
                is EditorEvent.Unlock -> {
                    if (activity == null) {
                        editor.unlockDeclined(e.step)
                    } else {
                        AppLock.authenticateForVault(activity) { ok -> if (ok) editor.unlocked(e.step) else editor.unlockDeclined(e.step) }
                    }
                }
            }
        }
    }
}

/** The title (new, edit, a copy, private, My card), Close, and Save, which fills in once there is something to save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(editor: EditorViewModel, args: EditorScreenArgs, scroll: TopAppBarScrollBehavior, onClose: () -> Unit) {
    ParleyTopBar(
        title = {
            Text(
                stringResource(
                    when {
                        args.meCard -> R.string.me_title
                        editor.isVault && !editor.temporaryNew ->
                            if ((args.vaultId ?: 0) > 0) R.string.edit_title_private else R.string.edit_title_new_private
                        args.contactId == null -> R.string.edit_title_new
                        args.rawId != null -> R.string.edit_title_copy
                        else -> R.string.edit_title_edit
                    },
                ),
                maxLines = 1,
            )
        },
        navigationIcon = { IconButton(onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.main_cancel)) } },
        actions = {
            // Save stays in the bar while the form scrolls; it's ready once there is something to save (new)
            // or something changed (existing), and says so by filling in.
            Button(onClick = editor::save, enabled = editor.canSave, modifier = Modifier.padding(end = 8.dp).heightIn(min = 40.dp)) {
                AnimatedContent(editor.saving, label = "save") { busy ->
                    if (busy) {
                        val desc = stringResource(R.string.editor_saving)
                        CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = desc }, strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.main_save))
                    }
                }
            }
        },
        scrollBehavior = scroll,
    )
}

/** What the form shows for [d]: which groups, which kinds the "Add" chips offer, and where things are kept. */
private class EditorShape(
    /** The key the call-screen picture is kept under: a private contact's is its Parley key, like on its page. */
    val lookup: String?,
    /** The labels a contact here can have (one chip per label title for a private contact; none for My card). */
    val accountGroups: List<GroupInfo>,
    /** Which website rows are profiles (Instagram, LinkedIn…). */
    val profileRow: List<Boolean>,
    val webKeys: List<Long>,
    /** A name detail holds something: the name block stays open. */
    val nameDetailsFilled: Boolean,
    val shownKinds: Set<EditorForm.Kind>,
    val choices: List<EditorForm.Kind>,
)

@Composable
private fun editorShape(vm: AppViewModel, editor: EditorViewModel, form: EditorFormUi, d: ContactDetails, args: EditorScreenArgs): EditorShape {
    val isVault = editor.isVault
    val lookup = editor.original?.lookupKey?.takeIf { !isVault && it.isNotEmpty() }
        ?: args.vaultId?.takeIf { it > 0 }?.let { ContactRef.privateKey(it) }
    // A private contact's labels are Parley's own membership of the address book's labels: one chip per label title
    // (its first group, as PrivateLabels resolves them). A visible temporary contact is phone-only, without labels.
    val account = editor.account
    val accountGroups = when {
        args.meCard -> emptyList()
        isVault -> editor.groups.distinctBy { it.title.trim() }
        editor.temporaryNew -> emptyList()
        else -> editor.groups.filter { it.account.type == account?.type && it.account.name == account?.name }
    }
    // Which website rows are profiles (Instagram, LinkedIn…): decided once per row, so a row never jumps to the
    // other group while its address is being typed (it shows where it belongs from the next opening).
    val webKeys = form.keys.keys(WEBSITES.group, d.websites.size)
    val profileRow = d.websites.mapIndexed { i, w ->
        form.profileRows.getOrPut(webKeys[i]) { SocialProfiles.fromWebsite(w.value, w.type, w.label) != null }
    }
    val nameDetailsFilled = listOf(
        d.prefix, d.middle, d.suffix, d.phoneticGiven, d.phoneticMiddle, d.phoneticFamily, d.nickname, d.pronouns, d.secondSurname, d.generation,
    ).any { it.isNotBlank() }
    // Only what the contact holds is on screen (plus name and a phone); everything else waits in the "Add" chips.
    val shownKinds = shownKinds(
        d, profileRow, editor.revealed, editor.moreName || nameDetailsFilled, accountGroups.isNotEmpty(), isVault, lookup, editor.background, vm,
    )
    // My card takes every field a contact does; only what belongs to where a contact is kept (labels, the
    // call-screen picture, a private contact's caller card) isn't offered there.
    val allowed = EditorForm.allowedKinds(hasLabels = accountGroups.isNotEmpty(), hasCallPicture = lookup != null, isPrivate = isVault)
    val choices = EditorForm.addChoices(shownKinds, blankKinds(d, profileRow), allowed)
    return EditorShape(lookup, accountGroups, profileRow, webKeys, nameDetailsFilled, shownKinds, choices)
}

/** The form: one column, or two on a wide screen (where it's saved, the photo and the name beside the fields). */
@Composable
private fun EditorBody(
    vm: AppViewModel,
    editor: EditorViewModel,
    form: EditorFormUi,
    d: ContactDetails,
    args: EditorScreenArgs,
    padding: PaddingValues,
    done: (Long?) -> Unit,
) {
    val shape = editorShape(vm, editor, form, d, args)
    // A new contact starts with the keyboard on First name, once (not again after rotation).
    var autoFocused by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(form.focusKey) {
        val k = form.focusKey ?: return@LaunchedEffect
        withFrameNanos { }
        form.keyIndex[k]?.let { i -> listState.animateScrollToItem(i) }
        withFrameNanos { }
        catching { form.requesters[k]?.requestFocus() }
        form.focusKey = null
    }
    LaunchedEffect(Unit) {
        if (!autoFocused) {
            autoFocused = true
            if (editor.isNew && d.given.isBlank() && d.family.isBlank()) form.focusKey = KEY_FIRST
        }
    }
    val header: @Composable () -> Unit = { EditorHeader(vm, editor, form, d, args, shape, done) }
    CompositionLocalProvider(LocalCountryIso provides vm.countryIso, LocalLocked provides editor.original?.readOnlyDataIds.orEmpty()) {
        BoxWithConstraints(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            if (wide) {
                // Two columns on wide screens and in landscape: where it's saved, photo and name beside the fields.
                Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) { header() }
                    LazyColumn(Modifier.weight(0.58f).fillMaxHeight(), state = listState, contentPadding = PaddingValues(top = 8.dp)) {
                        editorFields(vm, editor, form, d, args.meCard, shape, base = 0)
                    }
                }
            } else {
                // One column, at most 640 dp wide; the end column's button brings its own inset.
                val side = ((maxWidth - 640.dp) / 2).coerceAtLeast(16.dp)
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = side, end = side - 8.dp, top = 4.dp)) {
                    item(key = "header") { header() }
                    editorFields(vm, editor, form, d, args.meCard, shape, base = 1)
                }
            }
        }
    }
    EditorPickers(editor, form, d)
}

/**
 * The header: "Paste details" for a new contact, the photo on top, centred like the contact page's header, where it's
 * saved (or what My card shares), a duplicate warning, and the name with their name in their own language. Every field
 * below shares one left edge after the icon gutter; the Save-to line starts on that edge too.
 */
@Composable
private fun EditorHeader(
    vm: AppViewModel,
    editor: EditorViewModel,
    form: EditorFormUi,
    d: ContactDetails,
    args: EditorScreenArgs,
    shape: EditorShape,
    done: (Long?) -> Unit,
) {
    val nameOpen = editor.moreName || shape.nameDetailsFilled
    Column(Modifier.padding(bottom = FormTokens.groupGap)) {
        // "Paste details": a new contact filled from a copied signature or shared text, after a preview.
        if (editor.isNew && !args.meCard) {
            Box(Modifier.padding(start = FormTokens.gutter, top = 4.dp)) {
                PasteDetailsEntry(
                    vm, args.pasteText,
                    onFill = { fields -> form.update { PasteFill.into(it, fields) } },
                    onAddTo = { id, fields ->
                        // What was typed goes along with the pasted details.
                        vm.pendingPrefill = PasteFill.into(editor.draft ?: d, fields)
                        done(null)
                        vm.navigate(NavEvent.Route(if (id < 0) Routes.edit(vault = -id, prefill = true) else Routes.edit(id = id, prefill = true)))
                    },
                )
            }
        }
        val shownPhoto = editor.photo?.toString() ?: d.photoUri.takeUnless { editor.removePhoto }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
            EditorPhoto(vm, editor, d.composedName.ifBlank { d.nickname.ifBlank { d.company } }, shownPhoto)
        }
        Box(Modifier.padding(start = FormTokens.gutter, bottom = 4.dp)) { EditorSavedWhere(editor, args) }
        if (args.contactId == null && args.vaultId == null && !args.meCard) {
            DuplicateWarning(vm, d, onOpen = { id -> vm.navigate(NavEvent.Contact(id)) }) { id ->
                // "Add these details to her": continue in the existing contact's editor with this draft appended.
                vm.pendingPrefill = d
                done(null)
                vm.navigate(NavEvent.Route(Routes.edit(id = id, prefill = true)))
            }
        }
        NameBlock(
            d, expanded = nameOpen,
            // Kept open while a detail holds something.
            canToggle = !shape.nameDetailsFilled,
            onToggle = { editor.moreName = !editor.moreName }, first = form.fr(KEY_FIRST), nick = form.fr(KEY_NICK), update = form::update,
        )
        // Their name in their own language, under the name: offered when the name is in another script, they have a
        // language, or the name's details are open (it isn't one of the "Add" chips).
        if (EditorForm.Kind.NATIVE_NAME in shape.shownKinds) {
            NativeNameRow(
                d.nativeName, lockedRow(d.nativeNameId), form.fr(KEY_NATIVE),
                onChange = { n -> form.update { it.copy(nativeName = n) } },
                onRemove = {
                    editor.revealed = editor.revealed - EditorForm.Kind.NATIVE_NAME
                    form.update { it.copy(nativeName = NativeName()) }
                },
            )
        } else {
            NativeNameOffer(
                d.composedName, d.languages.isNotEmpty(), detailsOpen = nameOpen,
                onSpell = {
                    editor.revealed = editor.revealed + EditorForm.Kind.NATIVE_NAME
                    form.update(::withEnglishSpelling)
                    form.focusKey = KEY_FIRST
                },
                onAdd = { form.addKind(EditorForm.Kind.NATIVE_NAME, d) },
            )
        }
        if (editor.isVault && shownPhoto != null) {
            Text(
                stringResource(R.string.edit_private_photo), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = FormTokens.gutter + 16.dp, top = 4.dp),
            )
        }
    }
}

/** Where it's saved (and for how long), or for My card what its code and file share. */
@Composable
private fun EditorSavedWhere(editor: EditorViewModel, args: EditorScreenArgs) {
    val deviceName = stringResource(R.string.editor_account_device)
    val original = editor.original
    if (args.meCard) {
        MeShareLine(editor.meParts, editor::toggleMePart)
        return
    }
    SaveToLine(
        EditorSaveTo(
            args.vaultId, original != null, editor.privateNew, editor.temporaryNew, editor.temporary, editor.account, editor.accounts,
            // An expiry belongs to the whole contact, so it isn't offered when editing one of its copies.
            expiry = ExpiryState(editor.expiresAt, editor.expiryPick)
                .takeIf { args.rawId == null && (original?.lookupKey?.isNotEmpty() == true || (args.vaultId ?: 0L) > 0L) },
            systemDefault = editor.systemDefault,
        ),
        label = { a -> accountName(a, deviceName) },
        onAccount = editor::chooseAccount, onTemporary = editor::chooseTemporary,
        onTemporaryChange = editor::changeTemporary, onExpiry = editor::pickExpiry,
    )
}

/**
 * Builds the form's field groups into a lazy list: each item keyed, and where each focus key sits remembered (to
 * scroll to a new row). A group is one item per row, stacked as one segmented block.
 */
private class FieldList(private val scope: LazyListScope, val form: EditorFormUi, val d: ContactDetails, base: Int) {
    private var n = base

    /** [key]'s field is the next item. */
    fun at(key: Any) {
        form.keyIndex[key] = n
    }

    fun put(key: Any, content: @Composable LazyItemScope.() -> Unit) {
        form.keyIndex[key] = n++
        scope.item(key = key) { content() }
    }

    /**
     * A group: one item per row (keys from [RowKeys]) stacked as one segmented block, the group's icon in the
     * gutter of its first line, a small gap after its last. [row] gets the row's index, key, gutter and shape.
     * With [swap] (rows i and j of the group trade places) and two rows or more, each row can move up or down
     * ([LocalRowMoves]).
     */
    fun group(
        icon: ImageVector,
        title: Int,
        rowKeys: List<Long>,
        gap: Dp,
        swap: ((Int, Int) -> Unit)? = null,
        row: @Composable (Int, Long, Lead, Shape) -> Unit,
    ) {
        rowKeys.forEachIndexed { i, k ->
            put(k) {
                val lead = if (i == 0) Lead(icon, stringResource(title)) else Lead.None
                val bottom = if (i == rowKeys.lastIndex) FormTokens.groupGap else gap
                val moves = if (swap == null || rowKeys.size < 2) null else RowMoves(
                    up = if (i > 0) { { swap(i, i - 1) } } else null,
                    down = if (i < rowKeys.lastIndex) { { swap(i, i + 1) } } else null,
                )
                CompositionLocalProvider(LocalRowMoves provides moves) {
                    Box(Modifier.animateItem().padding(bottom = bottom)) { row(i, k, lead, formFieldShape(i, rowKeys.size)) }
                }
            }
        }
    }

    /** A group of [kind]'s rows; [only] keeps the rows of a shared list that belong to this group. */
    fun multi(kind: MultiKind, only: (Int) -> Boolean = { true }) {
        val items = kind.get(d)
        val rowKeys = form.keys.keys(kind.group, items.size)
        val idx = items.indices.filter(only)
        // Every row of the kind counts: the save orders the whole list (profiles and websites share one).
        val swap = if (!form.movable(items.map { it.id })) null else { a: Int, b: Int ->
            form.swapRows(kind.group, idx[a], idx[b], kind.get, kind.set)
        }
        group(kind.icon, kind.title, idx.map { rowKeys[it] }, FormTokens.segmentGap, swap) { j, k, lead, shape ->
            val i = idx.getOrNull(j) ?: return@group
            val item = items.getOrNull(i) ?: return@group
            MultiRow(kind, item, form.fr(k), lead, shape,
                onChange = { n2 -> form.update { kind.set(it, kind.get(it).toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
                onRemove = { form.removeRow(kind.group, i) { kind.set(it, kind.get(it).filterIndexed { j, _ -> j != i }) } },
            )
        }
    }
}

/**
 * The field groups (lazy, animated rows); [base] is the lazy index of the first one (to scroll to a new row): ways to
 * reach them, then about them, then the "Add" chips, in their groups.
 */
private fun LazyListScope.editorFields(
    vm: AppViewModel,
    editor: EditorViewModel,
    form: EditorFormUi,
    d: ContactDetails,
    meCard: Boolean,
    shape: EditorShape,
    base: Int,
) {
    form.keyIndex.clear()
    // The name fields live in the header item (index 0) when it's part of this list.
    if (base == 1) { form.keyIndex[KEY_FIRST] = 0; form.keyIndex[KEY_NICK] = 0; form.keyIndex[KEY_NATIVE] = 0 }
    val list = FieldList(this, form, d, base)
    list.reachGroups(shape)
    list.aboutGroups(vm, editor, meCard, shape)
    list.addChipsRow(shape)
    list.put("end") { Spacer(Modifier.height(24.dp)) }
}

/** Phones, e-mails, work and dates, then [placeGroups]. */
private fun FieldList.reachGroups(shape: EditorShape) {
    multi(PHONES)
    multi(EMAILS)

    if (EditorForm.Kind.WORK in shape.shownKinds) {
        at(KEY_COMPANY)
        put("work") {
            val workLocked = lockedRow(d.orgId)
            FormRow(Icons.Rounded.Business, stringResource(R.string.editor_work), Modifier.animateItem().padding(bottom = FormTokens.groupGap)) {
                EditorField(
                    stringResource(R.string.edit_company), d.company, shape = formFieldShape(0, 3), cap = KeyboardCapitalization.Words,
                    locked = workLocked, focus = form.fr(KEY_COMPANY),
                ) { v -> form.update { it.copy(company = v) } }
                Spacer(Modifier.height(FormTokens.segmentGap))
                EditorField(
                    stringResource(R.string.edit_job_title), d.title, shape = formFieldShape(1, 3), cap = KeyboardCapitalization.Words,
                    locked = workLocked,
                ) { v -> form.update { it.copy(title = v) } }
                Spacer(Modifier.height(FormTokens.segmentGap))
                EditorField(
                    stringResource(R.string.edit_department), d.department, shape = formFieldShape(2, 3), cap = KeyboardCapitalization.Words,
                    locked = workLocked,
                ) { v -> form.update { it.copy(department = v) } }
            }
        }
    }

    if (d.events.isNotEmpty()) {
        val swap = if (!form.movable(d.events.map { it.id })) null else { a: Int, b: Int ->
            form.swapRows(G_DATE, a, b, { it.events }) { c, l -> c.copy(events = l) }
        }
        group(Icons.Rounded.Cake, R.string.edit_important_dates, form.keys.keys(G_DATE, d.events.size), FormTokens.segmentGap, swap) { i, k, lead, shape ->
            val ev = d.events.getOrNull(i) ?: return@group
            DateRow(
                ev, lead, shape, openPicker = form.pickDateFor == k, onPickerClosed = { if (form.pickDateFor == k) form.pickDateFor = null },
                onChange = { n2 -> form.update { it.copy(events = it.events.toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
                onRemove = { form.removeRow(G_DATE, i) { it.copy(events = it.events.filterIndexed { j, _ -> j != i }) } },
            )
        }
    }

    placeGroups(shape)
}

/** Addresses (with their map links), then [webGroups]. */
private fun FieldList.placeGroups(shape: EditorShape) {
    val addressLinks = AddressMapLinks.matches(d)
    if (d.addresses.isNotEmpty()) {
        // Each address is its own block of lines, so addresses sit a little apart.
        // A map link follows its address by the address's label, so it moves along.
        val swap = if (!form.movable(d.addresses.map { it.id })) null else { a: Int, b: Int ->
            form.swapRows(G_ADDR, a, b, { it.addresses }) { c, l -> c.copy(addresses = l) }
        }
        group(Icons.Rounded.Place, R.string.detail_address, form.keys.keys(G_ADDR, d.addresses.size), FormTokens.groupGap, swap) { i, k, lead, _ ->
            val a = d.addresses.getOrNull(i) ?: return@group
            AddressRow(
                a, form.fr(k), lead,
                mapLink = addressLinks[i]?.let { d.websites.getOrNull(it)?.value },
                onMapLink = { form.mapLinkFor = i },
                onRemoveMapLink = {
                    addressLinks[i]?.let { w -> form.keys.removed(WEBSITES.group, w) }
                    form.update { AddressMapLinks.withoutLink(it, i) }
                },
                onChange = { n2 -> form.update { it.copy(addresses = it.addresses.toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
                // The address's map link goes with it.
                onRemove = {
                    addressLinks[i]?.let { w -> form.keys.removed(WEBSITES.group, w) }
                    form.removeRow(G_ADDR, i) {
                        AddressMapLinks.withoutLink(it, i).let { c -> c.copy(addresses = c.addresses.filterIndexed { j, _ -> j != i }) }
                    }
                },
            )
        }
    }

    webGroups(shape)
}

/** Handles, then [profileGroups]. */
private fun FieldList.webGroups(shape: EditorShape) {
    if (d.handles.isNotEmpty()) {
        val swap = if (!form.movable(d.handles.map { it.id })) null else { a: Int, b: Int ->
            form.swapRows(G_HANDLE, a, b, { it.handles }) { c, l -> c.copy(handles = l) }
        }
        group(Icons.Rounded.Forum, R.string.edit_handles, form.keys.keys(G_HANDLE, d.handles.size), FormTokens.segmentGap, swap) { i, k, lead, _ ->
            val h = d.handles.getOrNull(i) ?: return@group
            HandleRow(
                h, form.fr(k), lead, i, d.handles.size,
                onChange = { n2 -> form.update { it.copy(handles = it.handles.toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
                onRemove = { form.removeRow(G_HANDLE, i) { it.copy(handles = it.handles.filterIndexed { j, _ -> j != i }) } },
            )
        }
    }

    profileGroups(shape)
}

/** Profiles first (Instagram, LinkedIn…), then the other websites: one list of website rows underneath. */
private fun FieldList.profileGroups(shape: EditorShape) {
    val profileIdx = d.websites.indices.filter { shape.profileRow.getOrElse(it) { false } }
    // A read-only website anywhere in the list keeps the provider's order for profiles too (the save can't honour it).
    val profileSwap = if (!form.movable(d.websites.map { it.id })) null else { a: Int, b: Int ->
        form.swapRows(WEBSITES.group, profileIdx[a], profileIdx[b], WEBSITES.get, WEBSITES.set)
    }
    val profileKeys = profileIdx.map { shape.webKeys[it] }
    group(Icons.Rounded.AlternateEmail, R.string.edit_profiles, profileKeys, FormTokens.segmentGap, profileSwap) { j, k, lead, rowShape ->
        val i = profileIdx.getOrNull(j) ?: return@group
        val w = d.websites.getOrNull(i) ?: return@group
        // Never drops out mid-typing: a value that reads as no profile keeps the row's own service.
        val p = SocialProfiles.fromWebsite(w.value, w.type, w.label)
            ?: SocialProfiles.labelled(w.type, w.label)?.let { Profile(it, "") } ?: return@group
        ProfileRow(
            w, p, form.fr(k), lead.icon, lead.title, rowShape, locked = lockedRow(w.id),
            onChange = { n2 -> form.update { it.copy(websites = it.websites.toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
            onRemove = { form.removeRow(WEBSITES.group, i) { it.copy(websites = it.websites.filterIndexed { x, _ -> x != i }) } },
        )
    }
    if (shape.profileRow.any { !it }) multi(WEBSITES) { i -> !shape.profileRow.getOrElse(i) { false } }
}

/** Relations, then [otherGroups] and [aboutRows]. */
private fun FieldList.aboutGroups(vm: AppViewModel, editor: EditorViewModel, meCard: Boolean, shape: EditorShape) {
    if (d.relations.isNotEmpty()) {
        val swap = if (!form.movable(d.relations.map { it.id })) null else { a: Int, b: Int ->
            form.swapRows(G_REL, a, b, { it.relations }) { c, l -> c.copy(relations = l) }
        }
        group(Icons.Rounded.People, R.string.edit_relations, form.keys.keys(G_REL, d.relations.size), FormTokens.segmentGap, swap) { i, k, lead, rowShape ->
            val item = d.relations.getOrNull(i) ?: return@group
            RelationRow(
                vm, item, form.fr(k), lead, rowShape,
                storedIn = if (meCard || editor.isVault) null else editor.account ?: AccountRef(null, null),
                onChange = { n2 -> form.update { it.copy(relations = it.relations.toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
                onPicked = editor::linkRelation,
                // The row becomes a relation kept in Parley only: nothing of it goes to the phone's contacts.
                onKeepInParley = { kept, link ->
                    editor.linkRelation(kept.value, link)
                    form.removeRow(G_REL, i) {
                        it.copy(relations = it.relations.filterIndexed { j, _ -> j != i }, parleyRelations = it.parleyRelations + kept)
                    }
                },
                onRemove = { form.removeRow(G_REL, i) { it.copy(relations = it.relations.filterIndexed { j, _ -> j != i }) } },
            )
        }
    }

    otherGroups()
    aboutRows(vm, editor, meCard, shape)
}

/** Relations kept in Parley only, and custom fields. */
private fun FieldList.otherGroups() {
    if (d.parleyRelations.isNotEmpty()) {
        val parleyKeys = form.keys.keys(G_PARLEY_REL, d.parleyRelations.size)
        group(Icons.Rounded.Lock, R.string.edit_parley_relations, parleyKeys, FormTokens.segmentGap) { i, _, lead, rowShape ->
            val item = d.parleyRelations.getOrNull(i) ?: return@group
            ParleyRelationRow(
                item, lead, rowShape,
                onChange = { n2 ->
                    form.update { it.copy(parleyRelations = it.parleyRelations.toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) }
                },
                onRemove = { form.removeRow(G_PARLEY_REL, i) { it.copy(parleyRelations = it.parleyRelations.filterIndexed { j, _ -> j != i }) } },
            )
        }
    }

    if (d.customFields.isNotEmpty()) {
        val customKeys = form.keys.keys(G_CUSTOM, d.customFields.size)
        val swap = if (!form.movable(d.customFields.map { it.id })) null else { a: Int, b: Int ->
            form.swapRows(G_CUSTOM, a, b, { it.customFields }) { c, l -> c.copy(customFields = l) }
        }
        group(Icons.AutoMirrored.Rounded.ShortText, R.string.edit_custom_fields, customKeys, FormTokens.segmentGap, swap) { i, k, lead, _ ->
            val f = d.customFields.getOrNull(i) ?: return@group
            CustomFieldRow(
                f, lockedRow(f.id), form.fr(k), lead.icon, lead.title, i, d.customFields.size,
                onChange = { n2 -> form.update { it.copy(customFields = it.customFields.toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
                onRemove = { form.removeRow(G_CUSTOM, i) { it.copy(customFields = it.customFields.filterIndexed { j, _ -> j != i }) } },
            )
        }
    }
}

/** Languages, citizenship, labels, the note, "When they call" and the call-screen picture. */
private fun FieldList.aboutRows(vm: AppViewModel, editor: EditorViewModel, meCard: Boolean, shape: EditorShape) {
    if (EditorForm.Kind.LANGUAGE in shape.shownKinds) {
        at(KEY_LANGUAGE)
        put("language") {
            LanguagesRow(d.languages, d.languageIds.any { lockedRow(it) }, form.fr(KEY_LANGUAGE), Icons.Rounded.Translate, Modifier.animateItem()) { v ->
                form.update { it.copy(languages = v) }
            }
        }
    }

    if (EditorForm.Kind.CITIZENSHIP in shape.shownKinds) {
        put("citizenship") {
            CitizenshipRow(
                d.citizenships, d.citizenshipIds.any { lockedRow(it) }, Modifier.animateItem(), onAdd = { form.pickCountry = true },
                onRemove = { code -> form.update { it.copy(citizenships = it.citizenships - code) } },
            )
        }
    }

    if (EditorForm.Kind.LABELS in shape.shownKinds) {
        put("labels") { LabelsRow(shape.accountGroups, d.groupIds, Modifier.animateItem()) { ids -> form.update { it.copy(groupIds = ids) } } }
    }

    if (EditorForm.Kind.NOTE in shape.shownKinds) {
        at(KEY_NOTE)
        put("note") {
            FormRow(
                Icons.AutoMirrored.Rounded.Notes, stringResource(R.string.edit_notes), Modifier.animateItem().padding(bottom = FormTokens.groupGap),
            ) {
                ParleyFormField(
                    // My card's note goes into the QR code or vCard only when you tick it.
                    d.note, { v -> form.update { it.copy(note = v) } }, stringResource(R.string.edit_notes),
                    modifier = Modifier.fillMaxWidth().focusRequester(form.fr(KEY_NOTE)), singleLine = false, minLines = 2,
                    supporting = if (meCard) stringResource(R.string.me_note_hint) else null,
                    readOnly = lockedRow(d.noteId),
                    trailing = if (lockedRow(d.noteId)) { { LockIcon() } } else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
        }
    }

    if (EditorForm.Kind.WHEN_THEY_CALL in shape.shownKinds) {
        at(KEY_CONTEXT)
        put("call") { WhenTheyCallRow(d, form.fr(KEY_CONTEXT), Modifier.animateItem(), form::update) }
    }

    val lookup = shape.lookup
    if (lookup != null && EditorForm.Kind.CALL_BACKGROUND in shape.shownKinds) {
        put("bg") {
            // The picture editor names itself, so the gutter icon is only decoration here.
            FormRow(Icons.Rounded.Wallpaper, null, Modifier.animateItem().padding(bottom = FormTokens.groupGap)) {
                Box(Modifier.padding(top = 12.dp)) { CallBackgroundEditor(vm, lookup, editor.background, editor::changeBackground) }
            }
        }
    }
}

/** The one add control: the kinds this contact can still take, commonest first, in three small groups. */
private fun FieldList.addChipsRow(shape: EditorShape) {
    // The one add control: the kinds this contact can still take, commonest first, in three small groups (ways to
    // reach them, about them, when they call) so they don't read as one pile.
    val grouped = EditorForm.groupedChoices(shape.choices)
    if (grouped.isNotEmpty()) {
        put("add") {
            FormRow(Icons.Rounded.Add, stringResource(R.string.editor_add_title), Modifier.animateItem(), reserveEnd = false) {
                Column(Modifier.heightIn(min = FormTokens.fieldHeight), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    grouped.forEach { (g, kinds) ->
                        Text(
                            stringResource(chipGroupTitle(g)), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs).semantics { heading() },
                        )
                        AddChips(kinds.map { k -> AddChoice(kindIcon(k), stringResource(kindLabel(k))) { form.addKind(k, d) } })
                    }
                }
            }
        }
    }
}

private fun chipGroupTitle(g: EditorForm.ChipGroup): Int = when (g) {
    EditorForm.ChipGroup.CONTACT -> R.string.editor_add_group_contact
    EditorForm.ChipGroup.ABOUT -> R.string.editor_add_group_about
    EditorForm.ChipGroup.CALLS -> R.string.editor_add_group_calls
}

/** The pickers a row or chip opens: a profile's service, a citizenship's country, an address's map link. */
@Composable
private fun EditorPickers(editor: EditorViewModel, form: EditorFormUi, d: ContactDetails) {
    if (form.pickProfile) {
        ProfilePickerSheet(onDismiss = { form.pickProfile = false }) { service ->
            form.pickProfile = false
            val cur = editor.draft ?: d
            if (service == null) {
                form.addKind(EditorForm.Kind.WEBSITE, d)
            } else {
                editor.revealed = editor.revealed + EditorForm.Kind.PROFILE
                form.addRow(WEBSITES.group, cur.websites.size) {
                    it.copy(websites = it.websites + DataItem(type = SocialProfiles.TYPE_CUSTOM, label = service.label))
                }
            }
        }
    }
    if (form.pickCountry) {
        CountryPickerDialog(null, onDismiss = { form.pickCountry = false }) { code ->
            form.pickCountry = false
            editor.revealed = editor.revealed + EditorForm.Kind.CITIZENSHIP
            form.update { if (code in it.citizenships) it else it.copy(citizenships = it.citizenships + code) }
        }
    }
    form.mapLinkFor?.let { i ->
        MapLinkDialog(onDismiss = { form.mapLinkFor = null }) { place ->
            form.mapLinkFor = null
            form.update { AddressMapLinks.withLink(it, i, place) }
        }
    }
}

/**
 * The questions the editor asks: keep a very large photo whole, a contact changed elsewhere meanwhile, keep a private
 * contact's calls, and discard unsaved changes.
 */
@Composable
private fun EditorDialogs(editor: EditorViewModel, confirmDiscard: Boolean, setConfirmDiscard: (Boolean) -> Unit, done: (Long?) -> Unit) {
    // Keeping the picked photo whole: asked once per photo, only when it is very large or a HEIC with a location.
    editor.photoQuestion?.let { q ->
        val context = LocalContext.current
        val large = q == OriginalPhoto.Question.LARGE
        ConfirmDialog(
            title = stringResource(if (large) R.string.img_keep_large_title else R.string.img_keep_location_title),
            text = if (large) {
                stringResource(R.string.img_keep_large_text, Formatter.formatShortFileSize(context, editor.photoBytes))
            } else {
                stringResource(R.string.img_keep_location_text)
            },
            confirmLabel = stringResource(if (large) R.string.img_keep_large_whole else R.string.img_keep_location_keep),
            onConfirm = { editor.answerPhoto(true) },
            onDismiss = { editor.answerPhoto(false) },
            dismissLabel = stringResource(if (large) R.string.img_keep_large_jpeg else R.string.img_keep_location_jpeg),
        )
    }
    editor.conflict?.let { k ->
        ChangedElsewhereSheet(
            k, onTheirs = editor::useTheirs, onMine = editor::keepMine, onMerge = editor::merge, onDismiss = editor::dismissConflict,
        )
    }
    if (editor.askKeep != null) {
        ParleyDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.edit_keep_title)) },
            text = { Text(stringResource(R.string.edit_keep_body)) },
            confirmButton = { TextButton({ editor.answerKeep(true) }) { Text(stringResource(R.string.edit_keep)) } },
            dismissButton = { TextButton({ editor.answerKeep(false) }) { Text(stringResource(R.string.edit_still_delete)) } },
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.edit_discard_title),
            text = stringResource(R.string.editor_discard_body),
            confirmLabel = stringResource(R.string.edit_discard),
            onConfirm = { setConfirmDiscard(false); done(null) },
            onDismiss = { setConfirmDiscard(false) },
            destructive = true,
            dismissLabel = stringResource(R.string.edit_keep_editing),
        )
    }
}

/**
 * The groups on screen: phone always (a new contact starts with an empty number), the others once they hold
 * something or were just added with the "Add" chips ([revealed]).
 */
@Composable
private fun shownKinds(
    d: ContactDetails,
    profileRow: List<Boolean>,
    revealed: Set<EditorForm.Kind>,
    nameDetails: Boolean,
    hasLabels: Boolean,
    isVault: Boolean,
    lookup: String?,
    bgChange: BackgroundChange,
    vm: AppViewModel,
): Set<EditorForm.Kind> {
    val bgVersion by vm.c.people.backgrounds.version.collectAsStateWithLifecycle()
    val hasBackground = remember(lookup, bgVersion) { lookup != null && vm.c.people.backgrounds.forLookupKey(lookup) != null }
    return buildSet {
        fun show(k: EditorForm.Kind, has: Boolean) { if (has || k in revealed) add(k) }
        if (nameDetails) add(EditorForm.Kind.NAME_DETAILS)
        show(EditorForm.Kind.PHONE, d.phones.isNotEmpty())
        show(EditorForm.Kind.EMAIL, d.emails.isNotEmpty())
        show(EditorForm.Kind.WORK, d.company.isNotBlank() || d.title.isNotBlank() || d.department.isNotBlank())
        show(EditorForm.Kind.DATE, d.events.isNotEmpty())
        show(EditorForm.Kind.ADDRESS, d.addresses.isNotEmpty())
        show(EditorForm.Kind.WEBSITE, profileRow.any { !it })
        show(EditorForm.Kind.PROFILE, profileRow.any { it })
        show(EditorForm.Kind.HANDLE, d.handles.isNotEmpty())
        show(EditorForm.Kind.RELATION, d.relations.isNotEmpty())
        show(EditorForm.Kind.NOTE, d.note.isNotBlank())
        show(EditorForm.Kind.CUSTOM_FIELD, d.customFields.isNotEmpty())
        show(EditorForm.Kind.LANGUAGE, d.languages.any { it.isNotBlank() })
        show(EditorForm.Kind.NATIVE_NAME, !d.nativeName.isBlank)
        show(EditorForm.Kind.CITIZENSHIP, d.citizenships.isNotEmpty())
        if (hasLabels) show(EditorForm.Kind.LABELS, d.groupIds.isNotEmpty())
        if (isVault) show(EditorForm.Kind.WHEN_THEY_CALL, d.context.isNotBlank() || d.pinnedNote.isNotBlank())
        if (lookup != null) show(EditorForm.Kind.CALL_BACKGROUND, hasBackground || bgChange != BackgroundChange.None)
    }
}

/** Groups whose last row is still empty: their chip waits until it's filled, so empty rows don't pile up. */
private fun blankKinds(d: ContactDetails, profileRow: List<Boolean>): Set<EditorForm.Kind> = buildSet {
    if (d.phones.any { it.value.isBlank() }) add(EditorForm.Kind.PHONE)
    if (d.emails.any { it.value.isBlank() }) add(EditorForm.Kind.EMAIL)
    if (d.events.any { it.date.isBlank() }) add(EditorForm.Kind.DATE)
    if (d.addresses.any { it.isBlank }) add(EditorForm.Kind.ADDRESS)
    d.websites.forEachIndexed { i, w ->
        if (w.value.isBlank()) add(if (profileRow.getOrElse(i) { false }) EditorForm.Kind.PROFILE else EditorForm.Kind.WEBSITE)
    }
    if (d.handles.any { it.value.isBlank() }) add(EditorForm.Kind.HANDLE)
    if (d.relations.any { it.value.isBlank() }) add(EditorForm.Kind.RELATION)
    if (d.customFields.any { it.isBlank }) add(EditorForm.Kind.CUSTOM_FIELD)
}

@Suppress("CyclomaticComplexMethod") // One icon per kind.
private fun kindIcon(k: EditorForm.Kind): ImageVector = when (k) {
    EditorForm.Kind.PHONE -> Icons.Rounded.Phone
    EditorForm.Kind.EMAIL -> Icons.Rounded.Email
    EditorForm.Kind.WORK -> Icons.Rounded.Business
    EditorForm.Kind.DATE -> Icons.Rounded.Cake
    EditorForm.Kind.ADDRESS -> Icons.Rounded.Place
    EditorForm.Kind.NOTE -> Icons.AutoMirrored.Rounded.Notes
    EditorForm.Kind.WEBSITE -> Icons.Rounded.Language
    EditorForm.Kind.PROFILE -> Icons.Rounded.AlternateEmail
    EditorForm.Kind.RELATION -> Icons.Rounded.People
    EditorForm.Kind.HANDLE -> Icons.Rounded.Forum
    EditorForm.Kind.WHEN_THEY_CALL -> Icons.Rounded.PhoneInTalk
    EditorForm.Kind.LABELS -> Icons.AutoMirrored.Rounded.Label
    EditorForm.Kind.CALL_BACKGROUND -> Icons.Rounded.Wallpaper
    EditorForm.Kind.NAME_DETAILS -> Icons.Rounded.Badge
    EditorForm.Kind.CUSTOM_FIELD -> Icons.AutoMirrored.Rounded.ShortText
    EditorForm.Kind.LANGUAGE -> Icons.Rounded.Translate
    EditorForm.Kind.NATIVE_NAME -> Icons.Rounded.Abc
    EditorForm.Kind.CITIZENSHIP -> Icons.Rounded.Flag
}

@Suppress("CyclomaticComplexMethod") // One label per kind.
private fun kindLabel(k: EditorForm.Kind): Int = when (k) {
    EditorForm.Kind.PHONE -> R.string.detail_phone
    EditorForm.Kind.EMAIL -> R.string.detail_email
    EditorForm.Kind.WORK -> R.string.editor_work
    EditorForm.Kind.DATE -> R.string.edit_date
    EditorForm.Kind.ADDRESS -> R.string.detail_address
    EditorForm.Kind.NOTE -> R.string.edit_notes
    EditorForm.Kind.WEBSITE -> R.string.detail_website
    EditorForm.Kind.PROFILE -> R.string.edit_profile
    EditorForm.Kind.RELATION -> R.string.edit_relation
    EditorForm.Kind.HANDLE -> R.string.edit_handles
    EditorForm.Kind.WHEN_THEY_CALL -> R.string.edit_when_they_call
    EditorForm.Kind.LABELS -> R.string.home_labels
    EditorForm.Kind.CALL_BACKGROUND -> R.string.ppl_bg_title
    EditorForm.Kind.NAME_DETAILS -> R.string.edit_name_details
    EditorForm.Kind.CUSTOM_FIELD -> R.string.edit_custom_field
    EditorForm.Kind.LANGUAGE -> R.string.edit_languages
    EditorForm.Kind.NATIVE_NAME -> R.string.edit_native_name
    EditorForm.Kind.CITIZENSHIP -> R.string.edit_citizenship
}

/** The account's labels as chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelsRow(accountGroups: List<GroupInfo>, selected: Set<Long>, modifier: Modifier, onChange: (Set<Long>) -> Unit) {
    FormRow(Icons.AutoMirrored.Rounded.Label, stringResource(R.string.home_labels), modifier.padding(bottom = FormTokens.groupGap)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(min = FormTokens.fieldHeight)) {
            accountGroups.forEach { g ->
                val on = g.id in selected
                FilterChip(
                    on, { onChange(if (on) selected - g.id else selected + g.id) }, label = { Text(g.title) },
                    leadingIcon = if (on) { { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                    shape = ParleyShapes.pill,
                )
            }
        }
    }
}

/** Private contacts: who this is and a note, shown on the call screen (and, outside discreet mode, a missed call). */
@Composable
private fun WhenTheyCallRow(d: ContactDetails, focus: FocusRequester, modifier: Modifier, update: ((ContactDetails) -> ContactDetails) -> Unit) {
    FormRow(Icons.Rounded.PhoneInTalk, stringResource(R.string.edit_when_they_call), modifier.padding(bottom = FormTokens.groupGap)) {
        ParleyFormField(
            d.context, { v -> update { it.copy(context = v.take(120)) } }, stringResource(R.string.edit_who_is_this),
            modifier = Modifier.focusRequester(focus),
            placeholder = stringResource(R.string.edit_who_placeholder), shape = formFieldShape(0, 2),
            supporting = stringResource(R.string.edit_who_support),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(FormTokens.segmentGap))
        ParleyFormField(
            d.pinnedNote, { v -> update { it.copy(pinnedNote = v) } }, stringResource(R.string.detail_note_title),
            placeholder = stringResource(R.string.detail_note_placeholder), shape = formFieldShape(1, 2), singleLine = false, minLines = 2,
            supporting = stringResource(R.string.edit_private_call_note),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
    }
}

@Composable
private fun lockedRow(id: Long?): Boolean = id != null && id in LocalLocked.current

/**
 * The name as one block of the form, like every other group: the person icon in the gutter, first and last name,
 * and in the end column (where the other groups have ⊖) a chevron that adds prefix, middle, suffix, phonetic names
 * and nickname around them. The chevron sits centred on the block, so it reads as the block's own control; it's
 * hidden while a detail holds something (the block stays open, nothing looks lost) and for My card ([canToggle]).
 */
@Composable
private fun NameBlock(
    d: ContactDetails,
    expanded: Boolean,
    canToggle: Boolean,
    onToggle: () -> Unit,
    first: FocusRequester,
    nick: FocusRequester,
    update: ((ContactDetails) -> ContactDetails) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        val title = stringResource(R.string.editor_name_title)
        Box(Modifier.width(FormTokens.gutter).heightIn(min = FormTokens.fieldHeight), contentAlignment = Alignment.CenterStart) {
            Icon(
                Icons.Rounded.Person, null,
                Modifier.size(24.dp).semantics { contentDescription = title; heading() },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.weight(1f)) { NameFields(d, expanded, first, nick, update) }
        Box(
            Modifier.width(FormTokens.endColumn).heightIn(min = FormTokens.fieldHeight).align(Alignment.CenterVertically),
            contentAlignment = Alignment.Center,
        ) {
            if (canToggle) {
                IconButton(onToggle) {
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        stringResource(if (expanded) R.string.editor_name_less else R.string.editor_name_more),
                    )
                }
            }
        }
    }
}

/**
 * First and last name as one block, with prefix, middle, suffix, phonetic names, nickname and pronouns around them
 * when open. RFC 9554's second surname and generation join the block only for a contact that has them (another app
 * or a card wrote them), so the usual block stays as short as it was.
 */
@Composable
private fun NameFields(
    d: ContactDetails,
    expanded: Boolean,
    first: FocusRequester,
    nick: FocusRequester,
    update: ((ContactDetails) -> ContactDetails) -> Unit,
) {
    val locked = lockedRow(d.nameId)
    val partsLocked = lockedRow(d.namePartsId)
    val words = KeyboardCapitalization.Words
    val spec = ParleyMotion.spatial<IntSize>()
    // Once shown, the RFC 9554 lines stay while they're being cleared.
    var showParts by rememberSaveable { mutableStateOf(false) }
    if (d.secondSurname.isNotBlank() || d.generation.isNotBlank()) showParts = true

    val lines = nameLines(expanded, showParts)
    fun pos(key: String) = formFieldShape(lines.indexOf(key).coerceAtLeast(0), lines.size)
    val gap = Modifier.padding(top = FormTokens.segmentGap)
    val enter = expandVertically(spec) + fadeIn()
    val exit = shrinkVertically(spec) + fadeOut()
    Column {
        AnimatedVisibility(expanded, enter = enter, exit = exit) {
            val below = Modifier.padding(bottom = FormTokens.segmentGap)
            EditorField(stringResource(R.string.edit_prefix), d.prefix, below, shape = pos("prefix"), cap = words, locked = locked) { v ->
                update { it.copy(prefix = v) }
            }
        }
        EditorField(stringResource(R.string.edit_first_name), d.given, shape = pos("first"), cap = words, locked = locked, focus = first) { v ->
            update { it.copy(given = v) }
        }
        AnimatedVisibility(expanded, enter = enter, exit = exit) {
            EditorField(stringResource(R.string.edit_middle_name), d.middle, gap, shape = pos("middle"), cap = words, locked = locked) { v ->
                update { it.copy(middle = v) }
            }
        }
        EditorField(stringResource(R.string.edit_last_name), d.family, gap, shape = pos("last"), cap = words, locked = locked) { v ->
            update { it.copy(family = v) }
        }
        AnimatedVisibility(expanded, enter = enter, exit = exit) {
            Column {
                if (showParts) {
                    EditorField(
                        stringResource(R.string.edit_second_surname), d.secondSurname, gap, shape = pos("second"), cap = words, locked = partsLocked,
                    ) { v ->
                        update { it.copy(secondSurname = v) }
                    }
                }
                EditorField(stringResource(R.string.edit_suffix), d.suffix, gap, shape = pos("suffix"), cap = words, locked = locked) { v ->
                    update { it.copy(suffix = v) }
                }
                if (showParts) {
                    EditorField(
                        stringResource(R.string.edit_generation), d.generation, gap, shape = pos("generation"), cap = words, locked = partsLocked,
                    ) { v ->
                        update { it.copy(generation = v) }
                    }
                }
                EditorField(stringResource(R.string.edit_phonetic_first), d.phoneticGiven, gap, shape = pos("pg"), cap = words, locked = locked) { v ->
                    update { it.copy(phoneticGiven = v) }
                }
                EditorField(stringResource(R.string.edit_phonetic_middle), d.phoneticMiddle, gap, shape = pos("pm"), cap = words, locked = locked) { v ->
                    update { it.copy(phoneticMiddle = v) }
                }
                EditorField(stringResource(R.string.edit_phonetic_last), d.phoneticFamily, gap, shape = pos("pf"), cap = words, locked = locked) { v ->
                    update { it.copy(phoneticFamily = v) }
                }
                EditorField(
                    stringResource(R.string.edit_nickname), d.nickname, gap, shape = pos("nick"), cap = words, locked = lockedRow(d.nicknameId), focus = nick,
                ) { v ->
                    update { it.copy(nickname = v) }
                }
                // Pronouns (vCard PRONOUNS), shown beside the name on the page and the call screen; typed as people write them.
                EditorField(
                    stringResource(R.string.edit_pronouns), d.pronouns, gap, shape = pos("pronouns"), cap = KeyboardCapitalization.None,
                    locked = lockedRow(d.pronounsId),
                ) { v ->
                    update { it.copy(pronouns = v) }
                }
            }
        }
    }
}

/** The name block's lines in order, for the segment shapes: first and last, and around them the details when open. */
private fun nameLines(expanded: Boolean, parts: Boolean): List<String> = buildList {
    if (expanded) add("prefix")
    add("first")
    if (expanded) add("middle")
    add("last")
    if (expanded) {
        if (parts) add("second")
        add("suffix")
        if (parts) add("generation")
        addAll(listOf("pg", "pm", "pf", "nick", "pronouns"))
    }
}

/**
 * One phone, email or website: the value (flag and formatting for numbers), its type selector inside at the end, and
 * "⊖".
 */
@Suppress("CyclomaticComplexMethod") // Locked rows, types, phones' "More types" and hints, each a branch.
@Composable
private fun MultiRow(
    kind: MultiKind,
    item: DataItem,
    focus: FocusRequester,
    lead: Lead,
    shape: Shape,
    onChange: (DataItem) -> Unit,
    onRemove: () -> Unit,
) {
    val res = LocalResources.current
    val locked = item.id != null && item.id in LocalLocked.current
    val iso = LocalCountryIso.current
    val flag = if (kind === PHONES && item.value.length >= 6) remember(item.value, iso) { NumberInfo.flag(NumberInfo.region(item.value, iso)) } else null
    val current = if (item.type == 0) item.label ?: stringResource(R.string.edit_custom) else kind.typeLabel(res, item.type)
    var custom by remember { mutableStateOf(false) }
    // Phones: Android's other types behind "More types…", before "Custom…".
    var moreTypes by remember { mutableStateOf(false) }
    val more = if (kind === PHONES) listOf(stringResource(R.string.edit_phone_more_types)) else emptyList()
    FormRow(lead.icon, lead.title, end = if (!locked) { { RemoveButton(stringResource(kind.remove), onRemove) } } else null) {
        TypedLine(
            pill = if (locked) null else {
                {
                    TypePill(current, kind.types.map { kind.typeLabel(res, it) } + more + stringResource(R.string.edit_custom_more)) { t ->
                        when {
                            t in kind.types.indices -> onChange(item.copy(type = kind.types[t], label = null))
                            t < kind.types.size + more.size -> moreTypes = true
                            else -> custom = true
                        }
                    }
                }
            },
        ) { trailing ->
            EditorField(
                stringResource(kind.title), item.value, shape = shape, keyboard = kind.keyboard, locked = locked, focus = focus,
                prefix = flag?.let { "$it " }, phone = kind === PHONES,
                hint = kind.looksWrong?.takeIf { it(item.value) }?.let { stringResource(kind.hint) },
                support = if (locked) current else null,
                trailing = trailing,
            ) { v -> onChange(item.copy(value = v)) }
        }
    }
    if (custom) CustomLabelDialog(item.label.takeIf { item.type == 0 }, { custom = false }) { l -> onChange(item.copy(type = 0, label = l)) }
    if (moreTypes) PhoneMoreTypesDialog(item.type, { moreTypes = false }) { t -> moreTypes = false; onChange(item.copy(type = t, label = null)) }
}

/** A date: tapping the value opens the year-optional picker; its type pill at the end. */
@Composable
private fun DateRow(
    ev: EventItem, lead: Lead, shape: Shape, openPicker: Boolean, onPickerClosed: () -> Unit, onChange: (EventItem) -> Unit, onRemove: () -> Unit,
) {
    val res = LocalResources.current
    val locked = ev.id != null && ev.id in LocalLocked.current
    var picking by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    val pickLabel = stringResource(R.string.editor_pick_date)
    val source = remember { MutableInteractionSource() }
    LaunchedEffect(source, locked) {
        if (!locked) source.interactions.collect { if (it is PressInteraction.Release) picking = true }
    }
    FormRow(lead.icon, lead.title, end = if (!locked) { { RemoveButton(stringResource(R.string.edit_remove_date), onRemove) } } else null) {
        TypedLine(
            pill = if (locked) null else { { EventTypePill(ev, onChange) { custom = true } } },
        ) { trailing ->
            ParleyFormField(
                if (ev.date.isBlank()) "" else describeEvent(ev.date, false).substringBefore(" ·"), {}, stringResource(R.string.edit_date),
                modifier = Modifier.fillMaxWidth().semantics { if (!locked) onClick(label = pickLabel) { picking = true; true } },
                shape = shape, readOnly = true, placeholder = pickLabel,
                // The calendar it comes round by, when not the Gregorian one ("Chinese lunar calendar").
                supporting = if (locked) listOfNotNull(eventLabel(res, ev), calendarLine(res, ev.calendar)).joinToString(" · ")
                else calendarLine(res, ev.calendar),
                trailing = if (locked) { { LockIcon() } } else trailing,
                interactionSource = source,
            )
        }
    }
    if (custom) CustomLabelDialog(ev.label.takeIf { ev.type == Event.TYPE_CUSTOM }, { custom = false }) { onChange(ev.copy(type = Event.TYPE_CUSTOM, label = it)) }
    if (picking || openPicker) {
        EventDateDialog(
            ev.date, onDismiss = { picking = false; onPickerClosed() }, initialCalendar = ev.calendar,
            onPickCalendar = { date, cal -> onChange(ev.copy(date = date, calendar = cal)); picking = false; onPickerClosed() },
        ) { onChange(ev.copy(date = it)); picking = false; onPickerClosed() }
    }
}

/** A date's type: birthday, anniversary, other, death, or "Custom…" ([onCustom]). */
@Composable
private fun EventTypePill(ev: EventItem, onChange: (EventItem) -> Unit, onCustom: () -> Unit) {
    val res = LocalResources.current
    TypePill(
        eventLabel(res, ev),
        eventTypes.map { res.getString(Event.getTypeResource(it)) } + stringResource(R.string.edit_event_death) + stringResource(R.string.edit_custom_more),
    ) { t ->
        when (t) {
            in eventTypes.indices -> onChange(ev.copy(type = eventTypes[t], label = null))
            eventTypes.size -> onChange(ev.copy(type = Event.TYPE_CUSTOM, label = LifeEvents.DEATH_LABEL))
            else -> onCustom()
        }
    }
}

/**
 * One address as a block of lines: street (with the type pill), PO box and neighbourhood when it has them,
 * postcode and city, region and country; then its map link ("Add from map link").
 */
@Suppress("CyclomaticComplexMethod") // One branch per optional line of the block.
@Composable
private fun AddressRow(
    a: PostalItem,
    focus: FocusRequester,
    lead: Lead,
    mapLink: String?,
    onMapLink: () -> Unit,
    onRemoveMapLink: () -> Unit,
    onChange: (PostalItem) -> Unit,
    onRemove: () -> Unit,
) {
    val res = LocalResources.current
    val locked = a.id != null && a.id in LocalLocked.current
    val words = KeyboardCapitalization.Words
    val extra = a.poBox.isNotEmpty() || a.neighborhood.isNotEmpty()
    val lines = if (extra) 4 else 3
    var custom by remember { mutableStateOf(false) }
    val current = if (a.type == 0) a.label ?: stringResource(R.string.edit_custom) else StructuredPostal.getTypeLabel(res, a.type, a.label).toString()
    val gap = Modifier.padding(top = FormTokens.segmentGap)
    FormRow(lead.icon, lead.title, end = if (!locked) { { RemoveButton(stringResource(R.string.edit_remove_address), onRemove) } } else null) {
        val typed = !locked
        TypedLine(
            pill = if (!typed) null else {
                {
                    TypePill(
                        current, postalTypes.map { StructuredPostal.getTypeLabel(res, it, null).toString() } + stringResource(R.string.edit_custom_more),
                    ) { t ->
                        if (t in postalTypes.indices) onChange(a.copy(type = postalTypes[t], label = null)) else custom = true
                    }
                }
            },
        ) { trailing ->
            EditorField(
                stringResource(R.string.edit_street), a.street, shape = formFieldShape(0, lines), cap = words, locked = locked, focus = focus,
                support = if (locked) current else null, trailing = trailing,
            ) { onChange(a.copy(street = it)) }
        }
        /** Two fields sharing line [l] of the address block, split [w] : 1 − [w]. */
        @Composable
        fun pair(l: Int, w: Float, start: @Composable (Modifier, Shape) -> Unit, end: @Composable (Modifier, Shape) -> Unit) {
            Row(gap, horizontalArrangement = Arrangement.spacedBy(FormTokens.segmentGap)) {
                start(Modifier.weight(w), formFieldShape(l, lines, FieldSide.Start))
                end(Modifier.weight(1f - w), formFieldShape(l, lines, FieldSide.End))
            }
        }
        var line = 1
        if (extra) {
            pair(
                line++, 0.4f,
                { m, sh -> EditorField(stringResource(R.string.edit_po_box), a.poBox, m, shape = sh, locked = locked) { onChange(a.copy(poBox = it)) } },
                { m, sh ->
                    EditorField(stringResource(R.string.edit_neighbourhood), a.neighborhood, m, shape = sh, cap = words, locked = locked) {
                        onChange(a.copy(neighborhood = it))
                    }
                },
            )
        }
        pair(
            line++, 0.4f,
            { m, sh ->
                EditorField(stringResource(R.string.edit_postcode), a.postcode, m, shape = sh, cap = KeyboardCapitalization.Characters, locked = locked) {
                    onChange(a.copy(postcode = it))
                }
            },
            { m, sh -> EditorField(stringResource(R.string.edit_city), a.city, m, shape = sh, cap = words, locked = locked) { onChange(a.copy(city = it)) } },
        )
        pair(
            line, 0.5f,
            { m, sh ->
                EditorField(stringResource(R.string.edit_region), a.region, m, shape = sh, cap = words, locked = locked) {
                    onChange(a.copy(region = it))
                }
            },
            { m, sh ->
                EditorField(stringResource(R.string.edit_country), a.country, m, shape = sh, cap = words, locked = locked) {
                    onChange(a.copy(country = it))
                }
            },
        )
        // RFC 9554's room, floor, building… another app wrote: kept with the address, shown here, not edited.
        if (a.parts.isNotBlank()) AddressPartsLine(a.parts)
        if (!locked) AddressMapLinkRow(mapLink, onMapLink, onRemoveMapLink)
    }
    if (custom) CustomLabelDialog(a.label.takeIf { a.type == 0 }, { custom = false }) { l -> onChange(a.copy(type = 0, label = l)) }
}

/** One messenger handle: the handle with a per-service hint (a warning when it looks off) and the service pill. */
@Composable
private fun HandleRow(h: HandleItem, focus: FocusRequester, lead: Lead, index: Int, count: Int, onChange: (HandleItem) -> Unit, onRemove: () -> Unit) {
    val res = LocalResources.current
    val locked = h.id != null && h.id in LocalLocked.current
    val services = remember { HandleService.common + HandleService.entries.filter { it !in HandleService.common } }
    val problem = Handles.problem(h.service, h.value)
    val customService = h.service == HandleService.OTHER && !locked
    // A handle of another service takes two lines (handle, service name) inside its group's block.
    val top = index == 0
    val bottom = index == count - 1
    val shape = if (!customService) formFieldShape(index, count) else formFieldShape(if (top) 0 else 1, 3)
    val serviceShape = formFieldShape(if (bottom) 2 else 1, 3)
    FormRow(lead.icon, lead.title, end = if (!locked) { { RemoveButton(stringResource(R.string.edit_remove_handle), onRemove) } } else null) {
        TypedLine(
            pill = {
                TypePill(HandleText.service(res, h.service), services.map { HandleText.service(res, it) }, enabled = !locked) { t ->
                    onChange(h.copy(service = services[t], customProtocol = if (services[t] == HandleService.OTHER) h.customProtocol else null))
                }
            },
        ) { trailing ->
            EditorField(
                HandleText.label(res, h.handle), h.value, shape = shape,
                locked = locked, focus = focus,
                keyboard = if (h.service == HandleService.XMPP || h.service == HandleService.SIP) KeyboardType.Email else KeyboardType.Text,
                placeholder = h.service.placeholder.takeIf { it.isNotEmpty() },
                support = problem?.let { HandleText.problem(res, it) } ?: HandleText.hint(res, h.service), error = problem != null,
                trailing = if (locked) null else trailing,
            ) { onChange(h.copy(value = it)) }
        }
        if (customService) {
            EditorField(
                stringResource(R.string.edit_service_name), h.customProtocol.orEmpty(), Modifier.padding(top = FormTokens.segmentGap),
                shape = serviceShape,
                placeholder = stringResource(R.string.edit_service_placeholder),
            ) { onChange(h.copy(customProtocol = it)) }
        }
    }
}

/**
 * One relation: the name (or pick the contact), and its searchable vCard 4.0 type as a pill. [storedIn]: the account
 * of the contact being edited when other apps can read it (null for a private contact or My card). Picking a private
 * contact there first says that the name would be stored on this contact, and offers to keep the relation in Parley
 * only ([onKeepInParley]) instead.
 */
@Composable
private fun RelationRow(
    vm: AppViewModel,
    item: DataItem,
    focus: FocusRequester,
    lead: Lead,
    shape: Shape,
    storedIn: AccountRef?,
    onChange: (DataItem) -> Unit,
    onPicked: (String, RelationLinks.Link) -> Unit,
    onKeepInParley: (DataItem, RelationLinks.Link) -> Unit,
    onRemove: () -> Unit,
) {
    val locked = item.id != null && item.id in LocalLocked.current
    var typing by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    // The private contact picked, while asking where to keep the relation: its id, name and key.
    var askPrivate by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val label = relationTypeLabel(item)
    FormRow(lead.icon, lead.title, end = if (!locked) { { RemoveButton(stringResource(R.string.edit_remove_relation), onRemove) } } else null) {
        // The relation types are many and searchable, so the pill opens the search dialog rather than a menu.
        TypedLine(pill = if (locked) null else { { TypePill(label, emptyList(), onOpen = { typing = true }) } }) { trailing ->
            EditorField(
                stringResource(R.string.edit_relation), item.value, shape = shape, cap = KeyboardCapitalization.Words, locked = locked, focus = focus,
                support = if (locked) label else null,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ picking = true }) { Icon(Icons.Rounded.PersonSearch, stringResource(R.string.edit_choose_contact)) }
                        trailing?.invoke()
                    }
                },
            ) { v -> onChange(item.copy(value = v)) }
        }
    }
    if (typing) RelationTypePicker(item, onDone = { typing = false }, onChange = onChange)
    if (picking) {
        ContactChooserDialog(vm, onDismiss = { picking = false }) { id, name, key ->
            picking = false
            if (storedIn != null && ContactRef.isPrivateKey(key)) {
                askPrivate = arrayListOf(id.toString(), name, key)
            } else {
                onChange(item.copy(value = name))
                onPicked(name, RelationLinks.Link(key, id))
            }
        }
    }
    val asked = askPrivate
    if (asked != null && storedIn != null && asked.size == 3) {
        val (id, name, key) = asked
        val link = RelationLinks.Link(key, id.toLongOrNull() ?: 0L)
        PrivateRelationDialog(
            name, storedIn,
            onDismiss = { askPrivate = null },
            onKeepInParley = {
                askPrivate = null
                onKeepInParley(item.copy(id = null, value = name), link)
            },
            onStoreHere = {
                askPrivate = null
                onChange(item.copy(value = name))
                onPicked(name, link)
            },
        )
    }
}

/**
 * Picking private contact [name] as a relation of a contact other apps can read: says plainly that the name would be
 * stored on this contact (and synced by [storedIn]), and offers to keep the relation in Parley only.
 */
@Composable
private fun PrivateRelationDialog(name: String, storedIn: AccountRef, onDismiss: () -> Unit, onKeepInParley: () -> Unit, onStoreHere: () -> Unit) {
    val text = if (storedIn.isLocal) {
        stringResource(R.string.edit_private_relation_local, name)
    } else {
        stringResource(R.string.edit_private_relation_synced, name, storedIn.displayLabel)
    }
    ParleyDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text(stringResource(R.string.edit_private_relation_title, name)) },
        text = { Text(text) },
        confirmButton = { TextButton(onKeepInParley) { Text(stringResource(R.string.edit_private_relation_keep)) } },
        dismissButton = {
            Row {
                TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) }
                TextButton(onStoreHere) { Text(stringResource(R.string.edit_private_relation_store)) }
            }
        },
    )
}

/** How a relation's type reads on its pill ("Sister", a custom label). */
@Composable
private fun relationTypeLabel(item: DataItem): String {
    val res = LocalResources.current
    return RelationTypes.fromAndroid(item.type, item.label)?.let { RelationText.label(res, it) }
        ?: if (item.type == 0) item.label ?: stringResource(R.string.edit_custom) else Relation.getTypeLabel(res, item.type, null).toString()
}

/** The searchable relation types, for [item]'s pill. */
@Composable
private fun RelationTypePicker(item: DataItem, onDone: () -> Unit, onChange: (DataItem) -> Unit) {
    RelationTypeDialog(onDismiss = onDone) { t ->
        onDone()
        if (t != null) {
            val (type, lbl) = RelationTypes.toAndroid(t)
            onChange(item.copy(type = type, label = lbl))
        }
    }
}

/**
 * A relation kept in Parley only ([app.parley.common.people.ParleyRelations]): its name as picked, read-only, its type
 * as a pill, and remove. Never written to the phone's contacts.
 */
@Composable
private fun ParleyRelationRow(item: DataItem, lead: Lead, shape: Shape, onChange: (DataItem) -> Unit, onRemove: () -> Unit) {
    var typing by remember { mutableStateOf(false) }
    val label = relationTypeLabel(item)
    FormRow(lead.icon, lead.title, end = { RemoveButton(stringResource(R.string.edit_remove_relation), onRemove) }) {
        TypedLine(pill = { TypePill(label, emptyList(), onOpen = { typing = true }) }) { trailing ->
            ParleyFormField(
                item.value, {}, stringResource(R.string.edit_relation), shape = shape, modifier = Modifier.fillMaxWidth(), readOnly = true,
                trailing = trailing, supporting = stringResource(R.string.edit_parley_relation_support),
            )
        }
    }
    if (typing) RelationTypePicker(item, onDone = { typing = false }, onChange = onChange)
}
