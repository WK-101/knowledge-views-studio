package app.parley.ui.contact

import android.content.res.Resources
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.R
import app.parley.common.people.EditorForm
import app.parley.common.people.HandleService
import app.parley.common.people.Handles
import app.parley.common.people.LifeEvents
import app.parley.common.people.RelationLinks
import app.parley.common.people.RelationTypes
import app.parley.common.people.RowKeys
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.EventItem
import app.parley.data.HandleItem
import app.parley.data.NumberInfo
import app.parley.data.PostalItem
import app.parley.ui.Routes
import app.parley.ui.people.CallBackgroundEditor
import app.parley.ui.people.DuplicateWarning
import app.parley.ui.people.HandleText
import app.parley.ui.people.RelationText
import app.parley.ui.people.eventLabel
import app.parley.ui.screenViewModel
import kotlinx.coroutines.launch
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
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import app.parley.ui.FieldSide
import app.parley.ui.FormAddRow
import app.parley.ui.FormRow
import app.parley.ui.FormTokens
import app.parley.ui.ParleyFormField
import app.parley.ui.formFieldShape

private val phoneTypes = listOf(Phone.TYPE_MOBILE, Phone.TYPE_HOME, Phone.TYPE_WORK, Phone.TYPE_MAIN, Phone.TYPE_FAX_WORK, Phone.TYPE_OTHER)
private val emailTypes = listOf(Email.TYPE_HOME, Email.TYPE_WORK, Email.TYPE_MOBILE, Email.TYPE_OTHER)
private val postalTypes = listOf(StructuredPostal.TYPE_HOME, StructuredPostal.TYPE_WORK, StructuredPostal.TYPE_OTHER)
private val webTypes = listOf(Website.TYPE_HOMEPAGE, Website.TYPE_WORK, Website.TYPE_OTHER)
private val eventTypes = listOf(Event.TYPE_BIRTHDAY, Event.TYPE_ANNIVERSARY, Event.TYPE_OTHER)

// Focus keys of the fixed fields (row keys from RowKeys are positive).
private const val KEY_FIRST = -1L
private const val KEY_NICK = -2L
private const val KEY_NOTE = -3L
private const val KEY_COMPANY = -4L

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

/** What a form line shows in the start gutter: the group's icon and title, on the group's first line only. */
private class Lead(val icon: ImageVector?, val title: String?) {
    companion object {
        val None = Lead(null, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    done: (Long?) -> Unit,
) {
    // The edit lives in the screen's view model (and its saved state), so rotation, a theme, font or language change
    // and process death keep it; this composable only draws it.
    val editor: EditorViewModel = screenViewModel()
    LaunchedEffect(Unit) { editor.start(EditorArgs(contactId, prefillName, prefillPhone, prefillEmail, addPhone, prefill, vaultId, rawId)) }
    val latestDone by rememberUpdatedState(done)
    LaunchedEffect(editor) {
        editor.events.collect { e ->
            when (e) {
                is EditorEvent.Message -> vm.toast(e.text)
                is EditorEvent.Done -> latestDone(e.savedId)
            }
        }
    }
    val original = editor.original
    val account = editor.account
    val accounts = editor.accounts
    val groups = editor.groups
    val photo = editor.photo
    val removePhoto = editor.removePhoto
    val privateNew = editor.privateNew
    val saving = editor.saving
    val askKeep = editor.askKeep
    val moreName = editor.moreName
    val revealed = editor.revealed
    var confirmDiscard by remember { mutableStateOf(false) }
    var moreSheet by remember { mutableStateOf(false) }
    val isVault = editor.isVault
    val bgChange = editor.background
    val idx by vm.people.index.collectAsStateWithLifecycle()
    // Stable row keys (animations, focus) and the field to focus next.
    val keys = editor.keys
    val requesters = remember { HashMap<Long, FocusRequester>() }
    fun fr(key: Long) = requesters.getOrPut(key) { FocusRequester() }
    var focusKey by remember { mutableStateOf<Long?>(null) }
    var pickDateFor by remember { mutableStateOf<Long?>(null) }
    // The address whose "Add from map link" dialog is open.
    var mapLinkFor by rememberSaveable { mutableStateOf<Int?>(null) }
    // A new contact starts with the keyboard on First name, once (not again after rotation).
    var autoFocused by rememberSaveable { mutableStateOf(false) }

    // The system photo picker needs no storage permission (also for private contacts' encrypted photos, I6).
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) editor.pickPhoto(uri)
    }
    val d = editor.draft
    val changed = editor.changed
    val canSave = editor.canSave

    // Unsaved-changes guard with predictive back: the editor shrinks with the gesture, then asks.
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = changed && !saving && !confirmDiscard && askKeep == null && editor.conflict == null) { events ->
        try {
            events.collect { backProgress = it.progress }
            confirmDiscard = true
        } finally {
            backProgress = 0f
        }
    }
    val shrink by animateFloatAsState(backProgress, ParleyMotion.spatial(), label = "back")

    fun save() = editor.save()

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
        topBar = {
            ParleyTopBar(
                title = {
                    Text(
                        stringResource(
                            if (isVault) (if ((vaultId ?: 0) > 0) R.string.edit_title_private else R.string.edit_title_new_private)
                            else if (contactId == null) R.string.edit_title_new else if (rawId != null) R.string.edit_title_copy else R.string.edit_title_edit,
                        ),
                        maxLines = 1,
                    )
                },
                navigationIcon = { IconButton({ if (changed) confirmDiscard = true else done(null) }) { Icon(Icons.Rounded.Close, stringResource(R.string.main_cancel)) } },
                actions = {
                    // Save stays in the bar while the form scrolls; it's ready once there is something to save (new)
                    // or something changed (existing), and says so by filling in.
                    Button(onClick = ::save, enabled = canSave, modifier = Modifier.padding(end = 8.dp).heightIn(min = 40.dp)) {
                        AnimatedContent(saving, label = "save") { busy ->
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
        },
    ) { padding ->
        if (d == null) {
            val desc = stringResource(R.string.editor_loading)
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = desc })
            }
            return@ParleyScaffold
        }
        fun update(f: (ContactDetails) -> ContactDetails) = editor.update(f)
        fun shown(k: EditorForm.Kind, has: Boolean) = has || k in revealed
        val nameDetailsFilled = listOf(d.prefix, d.middle, d.suffix, d.phoneticGiven, d.phoneticFamily, d.nickname).any { it.isNotBlank() }
        val shownKinds = buildSet {
            if (moreName || nameDetailsFilled) add(EditorForm.Kind.NAME_DETAILS)
            if (d.events.isNotEmpty()) add(EditorForm.Kind.DATE)
            if (d.addresses.isNotEmpty()) add(EditorForm.Kind.ADDRESS)
            if (d.websites.isNotEmpty()) add(EditorForm.Kind.WEBSITE)
            if (d.handles.isNotEmpty()) add(EditorForm.Kind.HANDLE)
            if (d.relations.isNotEmpty()) add(EditorForm.Kind.RELATION)
            if (shown(EditorForm.Kind.NOTE, d.note.isNotBlank() || original != null)) add(EditorForm.Kind.NOTE)
        }

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
        fun addKind(k: EditorForm.Kind) {
            editor.revealed = revealed + k
            moreSheet = false
            val cur = editor.draft ?: d
            when (k) {
                EditorForm.Kind.NAME_DETAILS -> { editor.moreName = true; focusKey = KEY_NICK }
                EditorForm.Kind.DATE -> pickDateFor = addRow(G_DATE, cur.events.size) { it.copy(events = it.events + EventItem(type = Event.TYPE_BIRTHDAY)) }
                EditorForm.Kind.ADDRESS -> addRow(G_ADDR, cur.addresses.size) { it.copy(addresses = it.addresses + PostalItem(type = StructuredPostal.TYPE_HOME)) }
                EditorForm.Kind.WEBSITE -> addRow(WEBSITES.group, cur.websites.size) { it.copy(websites = it.websites + DataItem(type = Website.TYPE_HOMEPAGE)) }
                EditorForm.Kind.HANDLE -> addRow(G_HANDLE, cur.handles.size) { it.copy(handles = it.handles + HandleItem()) }
                EditorForm.Kind.RELATION -> addRow(G_REL, cur.relations.size) { it.copy(relations = it.relations + DataItem(type = Relation.TYPE_SPOUSE)) }
                EditorForm.Kind.NOTE -> focusKey = KEY_NOTE
            }
        }

        val listState = rememberLazyListState()
        val keyIndex = remember { HashMap<Any, Int>() }
        LaunchedEffect(focusKey) {
            val k = focusKey ?: return@LaunchedEffect
            withFrameNanos { }
            keyIndex[k]?.let { i -> listState.animateScrollToItem(i) }
            withFrameNanos { }
            runCatching { requesters[k]?.requestFocus() }
            focusKey = null
        }
        LaunchedEffect(Unit) {
            if (!autoFocused) {
                autoFocused = true
                if (editor.isNew && d.given.isBlank() && d.family.isBlank()) focusKey = KEY_FIRST
            }
        }

        // ---------------------------------------------------------------- header: photo, account, name, work
        val header: @Composable () -> Unit = {
            Column {
                val shownPhoto = photo?.toString() ?: d.photoUri.takeUnless { removePhoto }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhotoHeader(
                        d.composedName.ifBlank { d.nickname.ifBlank { d.company } }, shownPhoto,
                        onPick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onRemove = editor::clearPhoto,
                    )
                    if (isVault && shownPhoto != null) {
                        Text(
                            stringResource(R.string.edit_private_photo), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AccountLine(
                        vaultId = vaultId, isExisting = original != null, privateNew = privateNew, account = account, accounts = accounts,
                        label = { a -> idx.labelWithCount(a) },
                        onPick = editor::chooseAccount,
                    )
                    if (isVault) {
                        Text(
                            stringResource(R.string.edit_private_note), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        )
                    }
                    if (contactId == null && vaultId == null) {
                        DuplicateWarning(vm, d, onOpen = { id -> vm.navigate(NavEvent.Contact(id)) }) { id ->
                            // "Add these details to her": continue in the existing contact's editor with this draft appended.
                            vm.pendingPrefill = d
                            done(null)
                            vm.navigate(NavEvent.Route(Routes.edit(id = id, prefill = true)))
                        }
                    }
                }
                Spacer(Modifier.height(FormTokens.groupGap + 8.dp))
                NameGroup(
                    d, expanded = moreName || nameDetailsFilled, canCollapse = !nameDetailsFilled,
                    onToggle = { editor.moreName = !moreName }, first = fr(KEY_FIRST), nick = fr(KEY_NICK), update = ::update,
                )
                Spacer(Modifier.height(FormTokens.groupGap))
                val workLocked = lockedRow(d.orgId)
                FormRow(Icons.Rounded.Business, stringResource(R.string.editor_work)) {
                    EditorField(
                        stringResource(R.string.edit_company), d.company, shape = formFieldShape(0, 2), cap = KeyboardCapitalization.Words,
                        locked = workLocked, focus = fr(KEY_COMPANY),
                    ) { v -> update { it.copy(company = v) } }
                    Spacer(Modifier.height(FormTokens.segmentGap))
                    EditorField(
                        stringResource(R.string.edit_job_title), d.title, shape = formFieldShape(1, 2), cap = KeyboardCapitalization.Words, locked = workLocked,
                    ) { v -> update { it.copy(title = v) } }
                }
                Spacer(Modifier.height(FormTokens.groupGap))
            }
        }

        // ---------------------------------------------------------------- field groups (lazy, animated rows)
        /** The field groups; [base] is the lazy index of the first one (to scroll to a new row). */
        fun LazyListScope.fields(base: Int) {
            keyIndex.clear()
            var n = base
            // The name fields live in the header item (index 0) when it's part of this list.
            if (base == 1) { keyIndex[KEY_FIRST] = 0; keyIndex[KEY_NICK] = 0; keyIndex[KEY_COMPANY] = 0 }
            fun put(key: Any, content: @Composable LazyItemScope.() -> Unit) {
                keyIndex[key] = n++
                item(key = key) { content() }
            }

            /**
             * A group: one item per row (keys from [RowKeys]) stacked as one segmented block, the group's icon in the
             * gutter of its first line, then its "Add" row. [row] gets the row's index, key, gutter and field shape.
             */
            fun group(
                id: String,
                icon: ImageVector,
                title: Int,
                rowKeys: List<Long>,
                add: Int,
                gap: Dp,
                onAdd: () -> Unit,
                row: @Composable (Int, Long, Lead, Shape) -> Unit,
            ) {
                rowKeys.forEachIndexed { i, k ->
                    put(k) {
                        val lead = if (i == 0) Lead(icon, stringResource(title)) else Lead.None
                        Box(Modifier.animateItem().padding(bottom = gap)) { row(i, k, lead, formFieldShape(i, rowKeys.size)) }
                    }
                }
                put("$id:add") {
                    val lead = if (rowKeys.isEmpty()) Lead(icon, stringResource(title)) else Lead.None
                    FormAddRow(stringResource(add), onAdd, Modifier.animateItem().padding(bottom = FormTokens.groupGap - 4.dp), lead.icon, lead.title)
                }
            }
            fun multi(kind: MultiKind) {
                val items = kind.get(d)
                group(kind.group, kind.icon, kind.title, keys.keys(kind.group, items.size), kind.add, FormTokens.segmentGap, {
                    addRow(kind.group, items.size) { kind.set(it, kind.get(it) + DataItem(type = kind.newType)) }
                }) { i, k, lead, shape ->
                    val item = items.getOrNull(i) ?: return@group
                    MultiRow(kind, item, fr(k), lead, shape,
                        onChange = { n2 -> update { kind.set(it, kind.get(it).toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
                        onRemove = { removeRow(kind.group, i) { kind.set(it, kind.get(it).filterIndexed { j, _ -> j != i }) } },
                    )
                }
            }

            multi(PHONES)
            multi(EMAILS)

            if (d.events.isNotEmpty()) {
                group(
                    G_DATE, Icons.Rounded.Cake, R.string.edit_important_dates, keys.keys(G_DATE, d.events.size), R.string.edit_add_date, FormTokens.segmentGap,
                    {
                        pickDateFor = addRow(G_DATE, d.events.size) { it.copy(events = it.events + EventItem(type = Event.TYPE_BIRTHDAY)) }
                    },
                ) { i, k, lead, shape ->
                    val ev = d.events.getOrNull(i) ?: return@group
                    DateRow(
                        ev, lead, shape, openPicker = pickDateFor == k, onPickerClosed = { if (pickDateFor == k) pickDateFor = null },
                        onChange = { n2 -> update { it.copy(events = it.events.toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
                        onRemove = { removeRow(G_DATE, i) { it.copy(events = it.events.filterIndexed { j, _ -> j != i }) } },
                    )
                }
            }

            val addressLinks = AddressMapLinks.matches(d)
            if (d.addresses.isNotEmpty()) {
                // Each address is its own block of lines, so addresses sit a little apart.
                group(G_ADDR, Icons.Rounded.Place, R.string.detail_address, keys.keys(G_ADDR, d.addresses.size), R.string.edit_add_address, 12.dp, {
                    addRow(G_ADDR, d.addresses.size) { it.copy(addresses = it.addresses + PostalItem(type = StructuredPostal.TYPE_HOME)) }
                }) { i, k, lead, _ ->
                    val a = d.addresses.getOrNull(i) ?: return@group
                    AddressRow(
                        a, fr(k), lead,
                        mapLink = addressLinks[i]?.let { d.websites.getOrNull(it)?.value },
                        onMapLink = { mapLinkFor = i },
                        onRemoveMapLink = {
                            addressLinks[i]?.let { w -> keys.removed(WEBSITES.group, w) }
                            update { AddressMapLinks.withoutLink(it, i) }
                        },
                        onChange = { n2 -> update { it.copy(addresses = it.addresses.toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
                        // The address's map link goes with it.
                        onRemove = {
                            addressLinks[i]?.let { w -> keys.removed(WEBSITES.group, w) }
                            removeRow(G_ADDR, i) {
                                AddressMapLinks.withoutLink(it, i).let { c -> c.copy(addresses = c.addresses.filterIndexed { j, _ -> j != i }) }
                            }
                        },
                    )
                }
            }

            if (d.handles.isNotEmpty()) {
                group(
                    G_HANDLE, Icons.Rounded.Forum, R.string.edit_handles, keys.keys(G_HANDLE, d.handles.size), R.string.edit_add_handle, FormTokens.segmentGap,
                    {
                        addRow(G_HANDLE, d.handles.size) { it.copy(handles = it.handles + HandleItem()) }
                    },
                ) { i, k, lead, _ ->
                    val h = d.handles.getOrNull(i) ?: return@group
                    HandleRow(
                        h, fr(k), lead, i, d.handles.size,
                        onChange = { n2 -> update { it.copy(handles = it.handles.toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
                        onRemove = { removeRow(G_HANDLE, i) { it.copy(handles = it.handles.filterIndexed { j, _ -> j != i }) } },
                    )
                }
            }

            if (d.websites.isNotEmpty()) multi(WEBSITES)

            if (d.relations.isNotEmpty()) {
                group(
                    G_REL, Icons.Rounded.People, R.string.edit_relations, keys.keys(G_REL, d.relations.size), R.string.edit_add_relation, FormTokens.segmentGap,
                    {
                        addRow(G_REL, d.relations.size) { it.copy(relations = it.relations + DataItem(type = Relation.TYPE_SPOUSE)) }
                    },
                ) { i, k, lead, shape ->
                    val item = d.relations.getOrNull(i) ?: return@group
                    RelationRow(
                        vm, item, fr(k), lead, shape,
                        onChange = { n2 -> update { it.copy(relations = it.relations.toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
                        onPicked = editor::linkRelation,
                        onRemove = { removeRow(G_REL, i) { it.copy(relations = it.relations.filterIndexed { j, _ -> j != i }) } },
                    )
                }
            }

            val accountGroups = if (isVault) emptyList() else groups.filter { it.account.type == account?.type && it.account.name == account?.name }
            if (accountGroups.isNotEmpty()) {
                put("labels") {
                    FormRow(
                        Icons.AutoMirrored.Rounded.Label, stringResource(R.string.home_labels), Modifier.animateItem().padding(bottom = FormTokens.groupGap),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(min = FormTokens.fieldHeight).padding(top = 4.dp),
                        ) {
                            accountGroups.forEach { g ->
                                val on = g.id in d.groupIds
                                FilterChip(
                                    on, { update { it.copy(groupIds = if (on) it.groupIds - g.id else it.groupIds + g.id) } }, label = { Text(g.title) },
                                    leadingIcon = if (on) { { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                                    shape = ParleyShapes.pill,
                                )
                            }
                        }
                    }
                }
            }

            if (EditorForm.Kind.NOTE in shownKinds) {
                keyIndex[KEY_NOTE] = n
                put("note") {
                    FormRow(
                        Icons.AutoMirrored.Rounded.Notes, stringResource(R.string.edit_notes), Modifier.animateItem().padding(bottom = FormTokens.groupGap),
                    ) {
                        ParleyFormField(
                            d.note, { v -> update { it.copy(note = v) } }, stringResource(R.string.edit_notes),
                            modifier = Modifier.fillMaxWidth().focusRequester(fr(KEY_NOTE)), singleLine = false, minLines = 3,
                            readOnly = lockedRow(d.noteId),
                            trailing = if (lockedRow(d.noteId)) { { LockIcon() } } else null,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        )
                    }
                }
            }

            if (isVault) {
                put("call") {
                    // Shown on the call screen (and, outside discreet mode, a missed-call notification).
                    FormRow(
                        Icons.Rounded.PhoneInTalk, stringResource(R.string.edit_when_they_call), Modifier.animateItem().padding(bottom = FormTokens.groupGap),
                    ) {
                        ParleyFormField(
                            d.context, { v -> update { it.copy(context = v.take(120)) } }, stringResource(R.string.edit_who_is_this),
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
            }

            val lookup = original?.lookupKey
            if (!isVault && !lookup.isNullOrEmpty()) {
                put("bg") {
                    // The picture editor names itself, so the gutter icon is only decoration here.
                    FormRow(Icons.Rounded.Wallpaper, null, Modifier.animateItem().padding(top = 4.dp, bottom = FormTokens.groupGap)) {
                        Box(Modifier.padding(top = 16.dp)) { CallBackgroundEditor(vm, lookup, bgChange, editor::changeBackground) }
                    }
                }
            }

            // "Add more info" offers only the kinds not on screen yet.
            if (EditorForm.addable(shownKinds).isNotEmpty()) {
                put("more") {
                    FormRow(null, null, Modifier.animateItem()) {
                        FilledTonalButton({ moreSheet = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.editor_more_info))
                        }
                    }
                }
            }
            put("end") { Spacer(Modifier.height(48.dp)) }
        }

        CompositionLocalProvider(LocalCountryIso provides vm.countryIso, LocalLocked provides original?.readOnlyDataIds.orEmpty()) {
            BoxWithConstraints(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
                val wide = maxWidth >= 720.dp
                if (wide) {
                    // Two columns on wide screens and in landscape: photo, account, name and work beside the fields.
                    Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { header() }
                        LazyColumn(Modifier.weight(0.58f).fillMaxHeight(), state = listState, contentPadding = PaddingValues(top = 16.dp)) { fields(0) }
                    }
                } else {
                    // One column, at most 640 dp wide; the end column's button brings its own 12 dp inset.
                    val side = ((maxWidth - 640.dp) / 2).coerceAtLeast(16.dp)
                    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = side, end = side - 8.dp)) {
                        item(key = "header") { header() }
                        fields(1)
                    }
                }
            }
        }

        mapLinkFor?.let { i ->
            MapLinkDialog(onDismiss = { mapLinkFor = null }) { place ->
                mapLinkFor = null
                update { AddressMapLinks.withLink(it, i, place) }
            }
        }

        if (moreSheet) {
            val entries = EditorForm.addable(shownKinds).map { k ->
                when (k) {
                    EditorForm.Kind.NAME_DETAILS -> MoreEntry(Icons.Rounded.Badge, stringResource(R.string.edit_name_details), stringResource(R.string.editor_more_name)) { addKind(k) }
                    EditorForm.Kind.DATE -> MoreEntry(Icons.Rounded.Cake, stringResource(R.string.edit_important_dates), stringResource(R.string.editor_more_date)) { addKind(k) }
                    EditorForm.Kind.ADDRESS -> MoreEntry(Icons.Rounded.Place, stringResource(R.string.detail_address), stringResource(R.string.editor_more_address)) { addKind(k) }
                    EditorForm.Kind.WEBSITE -> MoreEntry(Icons.Rounded.Language, stringResource(R.string.detail_website), stringResource(R.string.editor_more_website)) { addKind(k) }
                    EditorForm.Kind.HANDLE -> MoreEntry(Icons.Rounded.Forum, stringResource(R.string.edit_handles), stringResource(R.string.editor_more_handle)) { addKind(k) }
                    EditorForm.Kind.RELATION -> MoreEntry(Icons.Rounded.People, stringResource(R.string.edit_relations), stringResource(R.string.editor_more_relation)) { addKind(k) }
                    EditorForm.Kind.NOTE -> MoreEntry(Icons.AutoMirrored.Rounded.Notes, stringResource(R.string.edit_notes), stringResource(R.string.editor_more_note)) { addKind(k) }
                }
            }
            MoreInfoSheet(entries) { moreSheet = false }
        }
    }

    editor.conflict?.let { k ->
        ChangedElsewhereSheet(
            k, onTheirs = editor::useTheirs, onMine = editor::keepMine, onMerge = editor::merge, onDismiss = editor::dismissConflict,
        )
    }
    if (askKeep != null) {
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
            onConfirm = { confirmDiscard = false; done(null) },
            onDismiss = { confirmDiscard = false },
            destructive = true,
            dismissLabel = stringResource(R.string.edit_keep_editing),
        )
    }
}

/** "Save to" chip for new contacts (private or an account), or where an existing contact lives. */
@Composable
private fun AccountLine(
    vaultId: Long?,
    isExisting: Boolean,
    privateNew: Boolean,
    account: AccountRef?,
    accounts: List<AccountRef>,
    label: (AccountRef) -> String,
    onPick: (AccountRef?) -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when {
            vaultId != null -> InfoLine(Icons.Rounded.Lock, stringResource(R.string.editor_private_here))
            isExisting -> InfoLine(
                if (account?.isLocal != false) Icons.Rounded.PhoneAndroid else Icons.Rounded.AccountCircle,
                stringResource(R.string.edit_saved_in, account?.displayLabel ?: stringResource(R.string.detail_phone)),
            )
            else -> {
                var open by remember { mutableStateOf(false) }
                val privateLabel = stringResource(R.string.edit_private_only)
                val current = if (privateNew) privateLabel else account?.let(label) ?: stringResource(R.string.edit_phone_only)
                val change = stringResource(R.string.editor_change_account)
                Box {
                    AssistChip(
                        onClick = { open = true },
                        label = { Text(stringResource(R.string.editor_saving_to, current), maxLines = 2) },
                        leadingIcon = {
                            Icon(
                                when { privateNew -> Icons.Rounded.Lock; account == null || account.isLocal -> Icons.Rounded.PhoneAndroid; else -> Icons.Rounded.AccountCircle },
                                null, Modifier.size(18.dp),
                            )
                        },
                        trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp)) },
                        shape = ParleyShapes.pill,
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = null,
                        modifier = Modifier.semantics { onClick(label = change) { open = true; true } },
                    )
                    DropdownMenu(open, { open = false }, shape = ParleyShapes.tile) {
                        DropdownMenuItem(
                            text = { Text(privateLabel) }, leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                            trailingIcon = if (privateNew) { { Icon(Icons.Rounded.Check, null) } } else null,
                            onClick = { open = false; onPick(null) },
                        )
                        accounts.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(label(a)) },
                                leadingIcon = { Icon(if (a.isLocal) Icons.Rounded.PhoneAndroid else Icons.Rounded.AccountCircle, null) },
                                trailingIcon = if (!privateNew && a == account) { { Icon(Icons.Rounded.Check, null) } } else null,
                                onClick = { open = false; onPick(a) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun lockedRow(id: Long?): Boolean = id != null && id in LocalLocked.current

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(Modifier.semantics(mergeDescendants = true) {}.padding(horizontal = 8.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The name: first and last name as one block, with a chevron in the end column that adds prefix, middle, suffix,
 * phonetic names and nickname around them (kept open while any of them holds something).
 */
@Composable
private fun NameGroup(
    d: ContactDetails,
    expanded: Boolean,
    canCollapse: Boolean,
    onToggle: () -> Unit,
    first: FocusRequester,
    nick: FocusRequester,
    update: ((ContactDetails) -> ContactDetails) -> Unit,
) {
    val locked = lockedRow(d.nameId)
    val words = KeyboardCapitalization.Words
    val spec = ParleyMotion.spatial<IntSize>()
    val count = if (expanded) 8 else 2

    // Line positions in the block (for the segment shapes): prefix, first, middle, last, suffix, phonetic ×2, nickname.
    fun pos(full: Int, short: Int) = formFieldShape(if (expanded) full else short, count)
    val title = stringResource(R.string.edit_name)
    val gap = Modifier.padding(top = FormTokens.segmentGap)
    Column {
        AnimatedVisibility(expanded, enter = expandVertically(spec) + fadeIn(), exit = shrinkVertically(spec) + fadeOut()) {
            FormRow(Icons.Rounded.Person, title) {
                EditorField(
                    stringResource(R.string.edit_prefix), d.prefix, shape = pos(0, 0), cap = words, locked = locked,
                ) { v -> update { it.copy(prefix = v) } }
            }
        }
        FormRow(
            if (expanded) null else Icons.Rounded.Person, if (expanded) null else title, if (expanded) gap else Modifier,
            end = if (canCollapse || !expanded) {
                {
                    IconButton(onToggle) {
                        Icon(
                            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            stringResource(if (expanded) R.string.editor_name_less else R.string.editor_name_more),
                        )
                    }
                }
            } else null,
        ) {
            EditorField(
                stringResource(R.string.edit_first_name), d.given, shape = pos(1, 0), cap = words, locked = locked, focus = first,
            ) { v -> update { it.copy(given = v) } }
        }
        AnimatedVisibility(expanded, enter = expandVertically(spec) + fadeIn(), exit = shrinkVertically(spec) + fadeOut()) {
            FormRow(null, null, gap) {
                EditorField(
                    stringResource(R.string.edit_middle_name), d.middle, shape = pos(2, 0), cap = words, locked = locked,
                ) { v -> update { it.copy(middle = v) } }
            }
        }
        FormRow(null, null, gap) {
            EditorField(
                stringResource(R.string.edit_last_name), d.family, shape = pos(3, 1), cap = words, locked = locked,
            ) { v -> update { it.copy(family = v) } }
        }
        AnimatedVisibility(expanded, enter = expandVertically(spec) + fadeIn(), exit = shrinkVertically(spec) + fadeOut()) {
            Column {
                FormRow(null, null, gap) {
                    EditorField(
                        stringResource(R.string.edit_suffix), d.suffix, shape = pos(4, 1), cap = words, locked = locked,
                    ) { v -> update { it.copy(suffix = v) } }
                }
                FormRow(null, null, gap) {
                    EditorField(
                        stringResource(R.string.edit_phonetic_first), d.phoneticGiven, shape = pos(5, 1), cap = words, locked = locked,
                    ) { v -> update { it.copy(phoneticGiven = v) } }
                }
                FormRow(null, null, gap) {
                    EditorField(
                        stringResource(R.string.edit_phonetic_last), d.phoneticFamily, shape = pos(6, 1), cap = words, locked = locked,
                    ) { v -> update { it.copy(phoneticFamily = v) } }
                }
                FormRow(null, null, gap) {
                    EditorField(
                        stringResource(R.string.edit_nickname), d.nickname, shape = pos(7, 1), cap = words, locked = lockedRow(d.nicknameId), focus = nick,
                    ) { v -> update { it.copy(nickname = v) } }
                }
            }
        }
    }
}

/** One phone, email or website: the value (flag and formatting for numbers), its type pill at the end, and "⊖". */
@Composable
private fun MultiRow(kind: MultiKind, item: DataItem, focus: FocusRequester, lead: Lead, shape: Shape, onChange: (DataItem) -> Unit, onRemove: () -> Unit) {
    val res = LocalResources.current
    val locked = item.id != null && item.id in LocalLocked.current
    val iso = LocalCountryIso.current
    val flag = if (kind === PHONES && item.value.length >= 6) remember(item.value, iso) { NumberInfo.flag(NumberInfo.region(item.value, iso)) } else null
    val current = if (item.type == 0) item.label ?: stringResource(R.string.edit_custom) else kind.typeLabel(res, item.type)
    var custom by remember { mutableStateOf(false) }
    FormRow(lead.icon, lead.title, end = if (!locked) { { RemoveButton(stringResource(kind.remove), onRemove) } } else null) {
        TypedLine(
            pill = if (locked) null else {
                {
                    TypePill(current, kind.types.map { kind.typeLabel(res, it) } + stringResource(R.string.edit_custom_more)) { t ->
                        if (t in kind.types.indices) onChange(item.copy(type = kind.types[t], label = null)) else custom = true
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
                supporting = if (locked) eventLabel(res, ev) else null,
                trailing = if (locked) { { LockIcon() } } else trailing,
                interactionSource = source,
            )
        }
    }
    if (custom) CustomLabelDialog(ev.label.takeIf { ev.type == Event.TYPE_CUSTOM }, { custom = false }) { onChange(ev.copy(type = Event.TYPE_CUSTOM, label = it)) }
    if (picking || openPicker) {
        EventDateDialog(ev.date, onDismiss = { picking = false; onPickerClosed() }) { onChange(ev.copy(date = it)); picking = false; onPickerClosed() }
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
 * One address as a block of lines: street (with the type pill), PO box and neighbourhood when it has them (F25),
 * postcode and city, region and country; then its map link ("Add from map link").
 */
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
        TypedLine(
            pill = if (locked) null else {
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

/** One relation: the name (or pick the contact), and its searchable vCard 4.0 type as a pill. */
@Composable
private fun RelationRow(
    vm: AppViewModel,
    item: DataItem,
    focus: FocusRequester,
    lead: Lead,
    shape: Shape,
    onChange: (DataItem) -> Unit,
    onPicked: (String, RelationLinks.Link) -> Unit,
    onRemove: () -> Unit,
) {
    val res = LocalResources.current
    val locked = item.id != null && item.id in LocalLocked.current
    var typing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val label = RelationTypes.fromAndroid(item.type, item.label)?.let { RelationText.label(res, it) }
        ?: if (item.type == 0) item.label ?: stringResource(R.string.edit_custom) else Relation.getTypeLabel(res, item.type, null).toString()
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
    if (typing) {
        RelationTypeDialog(onDismiss = { typing = false }) { t ->
            typing = false
            if (t != null) {
                val (type, lbl) = RelationTypes.toAndroid(t)
                onChange(item.copy(type = type, label = lbl))
            }
        }
    }
    if (picking) {
        ContactChooserDialog(vm, onDismiss = { picking = false }) { id, name, key ->
            picking = false
            onChange(item.copy(value = name))
            onPicked(name, RelationLinks.Link(key, id))
        }
    }
}
