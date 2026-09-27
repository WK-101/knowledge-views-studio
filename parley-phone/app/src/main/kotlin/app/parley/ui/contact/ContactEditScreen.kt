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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.rounded.Email
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
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
import app.parley.data.GroupInfo
import app.parley.data.HandleItem
import app.parley.data.PostalItem
import app.parley.ui.people.BackgroundChange
import app.parley.ui.people.HandleText
import app.parley.ui.people.applyBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val phoneTypes = listOf(Phone.TYPE_MOBILE, Phone.TYPE_HOME, Phone.TYPE_WORK, Phone.TYPE_MAIN, Phone.TYPE_FAX_WORK, Phone.TYPE_OTHER)
private val emailTypes = listOf(Email.TYPE_HOME, Email.TYPE_WORK, Email.TYPE_MOBILE, Email.TYPE_OTHER)
private val postalTypes = listOf(StructuredPostal.TYPE_HOME, StructuredPostal.TYPE_WORK, StructuredPostal.TYPE_OTHER)
private val webTypes = listOf(Website.TYPE_HOMEPAGE, Website.TYPE_WORK, Website.TYPE_OTHER)
private val eventTypes = listOf(Event.TYPE_BIRTHDAY, Event.TYPE_ANNIVERSARY, Event.TYPE_OTHER)

/** Country used to interpret phone numbers typed in the editor. */
val LocalCountryIso = androidx.compose.runtime.staticCompositionLocalOf { "US" }

// E1: focus keys of the fixed fields (row keys from RowKeys are positive).
private const val KEY_FIRST = -1L
private const val KEY_NICK = -2L
private const val KEY_NOTE = -3L
private const val KEY_COMPANY = -4L

/** E1: phones, e-mails and websites share one row layout; this says how each differs. */
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
    "phone", Icons.Rounded.Phone, R.string.detail_phone, R.string.edit_add_phone, R.string.v34e_remove_phone, phoneTypes, Phone.TYPE_MOBILE, KeyboardType.Phone,
    { r, t -> Phone.getTypeLabel(r, t, null).toString() }, { it.phones }, { d, l -> d.copy(phones = l) }, EditorForm::phoneLooksWrong, R.string.v34e_phone_hint,
)
private val EMAILS = MultiKind(
    "email", Icons.Rounded.Email, R.string.detail_email, R.string.edit_add_email, R.string.v34e_remove_email, emailTypes, Email.TYPE_HOME, KeyboardType.Email,
    { r, t -> Email.getTypeLabel(r, t, null).toString() }, { it.emails }, { d, l -> d.copy(emails = l) }, EditorForm::emailLooksWrong, R.string.v34e_email_hint,
)
private val WEBSITES = MultiKind(
    "web", Icons.Rounded.Language, R.string.detail_website, R.string.edit_add_website, R.string.v34e_remove_website, webTypes, Website.TYPE_HOMEPAGE, KeyboardType.Uri,
    { r, t -> r.getString(when (t) { Website.TYPE_HOMEPAGE -> R.string.edit_web_homepage; Website.TYPE_WORK -> R.string.edit_web_work; else -> R.string.edit_web_other }) },
    { it.websites }, { d, l -> d.copy(websites = l) },
)

private const val G_ADDR = "addr"
private const val G_DATE = "date"
private const val G_HANDLE = "handle"
private const val G_REL = "rel"

/** E1: the draft without never-saved blank rows, so an added-and-left-empty row isn't a change. */
private fun ContactDetails.meaningful(): ContactDetails {
    fun m(l: List<DataItem>) = EditorForm.meaningful(l, { it.id == null }, { it.value.isBlank() })
    return copy(
        phones = m(phones), emails = m(emails), websites = m(websites), relations = m(relations),
        addresses = EditorForm.meaningful(addresses, { it.id == null }, { it.isBlank }),
        events = EditorForm.meaningful(events, { it.id == null }, { it.date.isBlank() }),
        handles = EditorForm.meaningful(handles, { it.id == null }, { it.value.isBlank() }),
    )
}

/** Every text of the draft (a contact holding only an address, a note or a website is fine, F24). */
private fun ContactDetails.texts(): List<String> =
    listOf(prefix, given, middle, family, suffix, nickname, company, title, note, phoneticGiven, phoneticFamily, context, pinnedNote) +
        (phones + emails + websites + relations).map { it.value } + events.map { it.date } + handles.map { it.value } +
        addresses.flatMap { listOf(it.street, it.poBox, it.neighborhood, it.city, it.region, it.postcode, it.country) }

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
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<ContactDetails?>(null) }
    var draft by remember { mutableStateOf<ContactDetails?>(null) }
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }
    var account by remember { mutableStateOf<AccountRef?>(null) }
    var groups by remember { mutableStateOf<List<GroupInfo>>(emptyList()) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var removePhoto by remember { mutableStateOf(false) }
    var moreName by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var askKeep by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var start by remember { mutableStateOf<ContactDetails?>(null) }
    var revealed by remember { mutableStateOf(emptySet<EditorForm.Kind>()) }
    var moreSheet by remember { mutableStateOf(false) }
    // I5: relations whose contact was chosen with the picker (name key → that contact).
    var pickedLinks by remember { mutableStateOf(emptyMap<String, RelationLinks.Link>()) }
    // New contacts go to the private vault when "Private by default" is on (the Save-to menu can change it).
    var privateNew by remember { mutableStateOf(false) }
    val isVault = vaultId != null || privateNew
    var bgChange by remember { mutableStateOf<BackgroundChange>(BackgroundChange.None) }
    val idx by vm.people.index.collectAsStateWithLifecycle()
    // E1: stable row keys (animations, focus) and the field to focus next.
    val keys = remember { RowKeys() }
    val requesters = remember { HashMap<Long, FocusRequester>() }
    fun fr(key: Long) = requesters.getOrPut(key) { FocusRequester() }
    var focusKey by remember { mutableStateOf<Long?>(null) }
    var pickDateFor by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(contactId) {
        if (contactId == null && vaultId == null) privateNew = vm.c.people.prefs.current().privateByDefault
        accounts = withContext(Dispatchers.IO) { vm.c.contacts.accounts() }
        groups = withContext(Dispatchers.IO) { vm.c.contacts.groups() }
        val s = vm.settings.value
        account = accounts.firstOrNull { it.type == s.defaultAccountType && it.name == s.defaultAccountName }
            ?: accounts.firstOrNull { it.type == "com.google" } ?: accounts.firstOrNull()
        fun withPhoneRow(e: ContactDetails) = if (e.phones.isEmpty()) e.copy(phones = listOf(DataItem(type = Phone.TYPE_MOBILE))) else e
        if (vaultId != null) {
            val e = if (vaultId > 0) {
                try {
                    vm.c.vault.details(vaultId)
                } catch (_: app.parley.data.vault.VaultCrypto.LockedException) {
                    vm.toast(res.getString(R.string.edit_unlock_first))
                    done(null)
                    return@LaunchedEffect
                } ?: ContactDetails()
            } else {
                prefill ?: ContactDetails()
            }
            draft = withPhoneRow(e)
            start = draft
            return@LaunchedEffect
        }
        if (contactId != null) {
            val d = if (rawId != null) vm.c.contacts.editableRaw(contactId, rawId) else vm.c.contacts.editable(contactId)
            original = d
            val loaded = withPhoneRow(d ?: ContactDetails())
            var e = d ?: ContactDetails()
            if (addPhone.isNotBlank()) e = e.copy(phones = e.phones + DataItem(value = addPhone, type = Phone.TYPE_MOBILE))
            if (prefill != null) e = app.parley.InsertPrefill.appendTo(e, prefill)
            draft = withPhoneRow(e)
            // E1: an added number or appended details count as a change, so Save is ready for them.
            start = loaded
            account = d?.rawContacts?.firstOrNull { it.id == d.editRawId }?.account ?: AccountRef(null, null)
        } else {
            if (prefill != null) {
                draft = withPhoneRow(prefill)
                start = null
                return@LaunchedEffect
            }
            val parts = prefillName.trim().split(Regex("\\s+"), limit = 2)
            draft = ContactDetails(
                given = parts.getOrElse(0) { "" },
                family = parts.getOrElse(1) { "" },
                phones = listOf(DataItem(value = prefillPhone, type = Phone.TYPE_MOBILE)),
                emails = if (prefillEmail.isNotBlank()) listOf(DataItem(value = prefillEmail, type = Email.TYPE_HOME)) else emptyList(),
            )
            start = draft
        }
    }

    // The system photo picker needs no storage permission (also for private contacts' encrypted photos, I6).
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { photo = uri; removePhoto = false }
    }
    val d = draft
    val isNew = original == null && (vaultId ?: 0L) <= 0L
    val changed = d != null && (start == null || d.meaningful() != start?.meaningful()) ||
        photo != null || removePhoto || bgChange != BackgroundChange.None
    val canSave = d != null && EditorForm.canSave(isNew, changed, EditorForm.hasContent(d.texts()) || photo != null, saving)

    // E1: unsaved-changes guard with predictive back: the editor shrinks with the gesture, then asks.
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = changed && !saving && !confirmDiscard && askKeep == null) { events ->
        try {
            events.collect { backProgress = it.progress }
            confirmDiscard = true
        } finally {
            backProgress = 0f
        }
    }
    val shrink by animateFloatAsState(backProgress, spring(stiffness = Spring.StiffnessMediumLow), label = "back")

    fun save() {
        val e = draft ?: return
        // A contact holding only an address, a note or a website is fine (F24); a completely empty one is not.
        val empty = !EditorForm.hasContent(e.texts())
        // Clearing one copy of a linked contact is allowed: that empty copy is removed and the others stay.
        val orig = original
        if (empty && photo == null && (orig == null || orig.rawContacts.size < 2 || orig.editRawId == null)) {
            vm.toast(res.getString(if (original == null) R.string.edit_add_name_first else R.string.edit_nothing_left))
            return
        }
        saving = true
        scope.launch {
            val id = try {
                if (isVault) {
                    val existing = vaultId?.takeIf { it > 0 }
                    val cleaned = e.copy(handles = e.handles.filter { it.value.isNotBlank() })
                    val id = vm.c.vault.save(existing, cleaned)
                    // I6: the encrypted caller photo.
                    val picked = photo
                    if (picked != null) {
                        // C1: decoded reduced and upright from the picked file, never read whole.
                        val bytes = withContext(Dispatchers.IO) { app.parley.data.ContactPhotoProcessor.process(context.contentResolver, picked) }
                        if (bytes == null || !vm.c.vault.setPhoto(id, bytes)) vm.toast(res.getString(R.string.edit_photo_failed))
                    } else if (removePhoto) {
                        vm.c.vault.removePhoto(id)
                    }
                    -id // negative ids mark vault contacts for the caller
                } else {
                    vm.c.contacts.save(original, e, account, photo, removePhoto)?.contactId.also { saved ->
                        original?.lookupKey?.let { key -> vm.applyBackground(key, bgChange) }
                        if (saved != null) rememberRelations(vm, saved, e, pickedLinks)
                    }
                }
            } catch (ex: Exception) {
                vm.toast(res.getString(R.string.edit_save_failed, ex.message.orEmpty()))
                null
            }
            saving = false
            if (id == null) return@launch
            // A temporary contact the user just edited for real: ask once whether to keep it (F2).
            val key = original?.lookupKey
            if (!isVault && !key.isNullOrEmpty() && vm.c.temporaries.needsKeepPrompt(key)) askKeep = key to id else done(id)
        }
    }

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier
            .graphicsLayer {
                val s = 1f - 0.08f * shrink
                scaleX = s
                scaleY = s
                shape = RoundedCornerShape((32 * shrink).dp)
                clip = shrink > 0f
            }
            .nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
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
                    // E1: Save is ready once there is something to save (new) or something changed (existing).
                    Button(onClick = ::save, enabled = canSave, modifier = Modifier.padding(end = 8.dp)) {
                        AnimatedContent(saving, label = "save") { busy ->
                            if (busy) {
                                val desc = stringResource(R.string.v34e_saving)
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
            val desc = stringResource(R.string.v34e_loading)
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = desc })
            }
            return@Scaffold
        }
        fun update(f: (ContactDetails) -> ContactDetails) { draft = f(draft ?: d) }
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
            revealed = revealed + k
            moreSheet = false
            val cur = draft ?: d
            when (k) {
                EditorForm.Kind.NAME_DETAILS -> { moreName = true; focusKey = KEY_NICK }
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

        // ---------------------------------------------------------------- header: photo, account, name
        val header: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val shownPhoto = photo?.toString() ?: d.photoUri.takeUnless { removePhoto }
                PhotoHeader(
                    d.composedName.ifBlank { d.nickname.ifBlank { d.company } }, shownPhoto,
                    onPick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onRemove = { photo = null; removePhoto = true },
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
                    onPick = { a -> if (a == null) privateNew = true else { privateNew = false; account = a } },
                )
                if (isVault) {
                    Text(
                        stringResource(R.string.edit_private_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
                if (contactId == null && vaultId == null) {
                    app.parley.ui.people.DuplicateWarning(vm, d, onOpen = { id -> vm.navigate(app.parley.NavEvent.Contact(id)) }) { id ->
                        // "Add these details to her": continue in the existing contact's editor with this draft appended.
                        vm.pendingPrefill = d
                        done(null)
                        vm.navigate(app.parley.NavEvent.Route(app.parley.ui.Routes.edit(id = id, prefill = true)))
                    }
                }
                NameCard(
                    d, expanded = moreName || nameDetailsFilled, canCollapse = !nameDetailsFilled,
                    onToggle = { moreName = !moreName }, first = fr(KEY_FIRST), nick = fr(KEY_NICK), update = ::update,
                )
                Segment(SegPos.Single) {
                    GroupHead(Icons.Rounded.Business, stringResource(R.string.v34e_work))
                    EditorField(stringResource(R.string.edit_company), d.company, cap = KeyboardCapitalization.Words, locked = lockedRow(d.orgId), focus = fr(KEY_COMPANY)) { v -> update { it.copy(company = v) } }
                    EditorField(stringResource(R.string.edit_job_title), d.title, cap = KeyboardCapitalization.Words, locked = lockedRow(d.orgId)) { v -> update { it.copy(title = v) } }
                    Spacer(Modifier.height(4.dp))
                }
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
            /** A group card: head, one item per row (keys from [RowKeys]), then its "Add" row. */
            fun group(id: String, icon: ImageVector, title: Int, rowKeys: List<Long>, add: Int?, onAdd: () -> Unit, row: @Composable (Int, Long) -> Unit) {
                put("$id:head") { Segment(SegPos.Top, Modifier.animateItem()) { GroupHead(icon, stringResource(title)) } }
                rowKeys.forEachIndexed { i, k -> put(k) { Segment(SegPos.Middle, Modifier.animateItem()) { row(i, k) } } }
                put("$id:foot") {
                    Segment(SegPos.Bottom, Modifier.animateItem()) { if (add != null) AddRow(stringResource(add), onAdd) else Spacer(Modifier.height(4.dp)) }
                }
                put("$id:gap") { Spacer(Modifier.height(12.dp)) }
            }
            fun multi(kind: MultiKind) {
                val items = kind.get(d)
                group(kind.group, kind.icon, kind.title, keys.keys(kind.group, items.size), kind.add, {
                    addRow(kind.group, items.size) { kind.set(it, kind.get(it) + DataItem(type = kind.newType)) }
                }) { i, k ->
                    val item = items.getOrNull(i) ?: return@group
                    MultiRow(kind, item, fr(k),
                        onChange = { n2 -> update { kind.set(it, kind.get(it).toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
                        onRemove = { removeRow(kind.group, i) { kind.set(it, kind.get(it).filterIndexed { j, _ -> j != i }) } },
                    )
                }
            }

            multi(PHONES)
            multi(EMAILS)

            if (d.events.isNotEmpty()) {
                group(G_DATE, Icons.Rounded.Cake, R.string.edit_important_dates, keys.keys(G_DATE, d.events.size), R.string.edit_add_date, {
                    pickDateFor = addRow(G_DATE, d.events.size) { it.copy(events = it.events + EventItem(type = Event.TYPE_BIRTHDAY)) }
                }) { i, k ->
                    val ev = d.events.getOrNull(i) ?: return@group
                    DateRow(
                        ev, openPicker = pickDateFor == k, onPickerClosed = { if (pickDateFor == k) pickDateFor = null },
                        onChange = { n2 -> update { it.copy(events = it.events.toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
                        onRemove = { removeRow(G_DATE, i) { it.copy(events = it.events.filterIndexed { j, _ -> j != i }) } },
                    )
                }
            }

            if (d.addresses.isNotEmpty()) {
                group(G_ADDR, Icons.Rounded.Place, R.string.detail_address, keys.keys(G_ADDR, d.addresses.size), R.string.edit_add_address, {
                    addRow(G_ADDR, d.addresses.size) { it.copy(addresses = it.addresses + PostalItem(type = StructuredPostal.TYPE_HOME)) }
                }) { i, k ->
                    val a = d.addresses.getOrNull(i) ?: return@group
                    AddressRow(
                        a, fr(k),
                        onChange = { n2 -> update { it.copy(addresses = it.addresses.toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
                        onRemove = { removeRow(G_ADDR, i) { it.copy(addresses = it.addresses.filterIndexed { j, _ -> j != i }) } },
                    )
                }
            }

            if (d.handles.isNotEmpty()) {
                group(G_HANDLE, Icons.Rounded.Forum, R.string.edit_handles, keys.keys(G_HANDLE, d.handles.size), R.string.edit_add_handle, {
                    addRow(G_HANDLE, d.handles.size) { it.copy(handles = it.handles + HandleItem()) }
                }) { i, k ->
                    val h = d.handles.getOrNull(i) ?: return@group
                    HandleRow(
                        h, fr(k),
                        onChange = { n2 -> update { it.copy(handles = it.handles.toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
                        onRemove = { removeRow(G_HANDLE, i) { it.copy(handles = it.handles.filterIndexed { j, _ -> j != i }) } },
                    )
                }
            }

            if (d.websites.isNotEmpty()) multi(WEBSITES)

            if (d.relations.isNotEmpty()) {
                group(G_REL, Icons.Rounded.People, R.string.edit_relations, keys.keys(G_REL, d.relations.size), R.string.edit_add_relation, {
                    addRow(G_REL, d.relations.size) { it.copy(relations = it.relations + DataItem(type = Relation.TYPE_SPOUSE)) }
                }) { i, k ->
                    val item = d.relations.getOrNull(i) ?: return@group
                    RelationRow(
                        vm, item, fr(k),
                        onChange = { n2 -> update { it.copy(relations = it.relations.toMutableList().also { l -> if (i in l.indices) l[i] = n2 }) } },
                        onPicked = { name, link -> pickedLinks = pickedLinks + (RelationLinks.nameKey(name) to link) },
                        onRemove = { removeRow(G_REL, i) { it.copy(relations = it.relations.filterIndexed { j, _ -> j != i }) } },
                    )
                }
            }

            val accountGroups = if (isVault) emptyList() else groups.filter { it.account.type == account?.type && it.account.name == account?.name }
            if (accountGroups.isNotEmpty()) {
                put("labels") {
                    Segment(SegPos.Single, Modifier.animateItem()) {
                        GroupHead(Icons.AutoMirrored.Rounded.Label, stringResource(R.string.home_labels))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 8.dp, bottom = 4.dp)) {
                            accountGroups.forEach { g ->
                                val on = g.id in d.groupIds
                                FilterChip(
                                    on, { update { it.copy(groupIds = if (on) it.groupIds - g.id else it.groupIds + g.id) } }, label = { Text(g.title) },
                                    leadingIcon = if (on) { { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                                )
                            }
                        }
                    }
                }
                put("labels:gap") { Spacer(Modifier.height(12.dp)) }
            }

            if (EditorForm.Kind.NOTE in shownKinds) {
                keyIndex[KEY_NOTE] = n
                put("note") {
                    Segment(SegPos.Single, Modifier.animateItem()) {
                        GroupHead(Icons.AutoMirrored.Rounded.Notes, stringResource(R.string.edit_notes))
                        OutlinedTextField(
                            d.note, { v -> update { it.copy(note = v) } }, label = { Text(stringResource(R.string.edit_notes)) },
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 8.dp).focusRequester(fr(KEY_NOTE)), minLines = 2, shape = FieldShape,
                            readOnly = lockedRow(d.noteId),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        )
                    }
                }
                put("note:gap") { Spacer(Modifier.height(12.dp)) }
            }

            if (isVault) {
                put("call") {
                    Segment(SegPos.Single, Modifier.animateItem()) {
                        // I6: shown on the call screen (and, outside discreet mode, a missed-call notification).
                        GroupHead(Icons.Rounded.PhoneInTalk, stringResource(R.string.edit_when_they_call))
                        Column(Modifier.padding(end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                d.context, { v -> update { it.copy(context = v.take(120)) } }, label = { Text(stringResource(R.string.edit_who_is_this)) },
                                placeholder = { Text(stringResource(R.string.edit_who_placeholder)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape,
                                supportingText = { Text(stringResource(R.string.edit_who_support)) },
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                            )
                            OutlinedTextField(
                                d.pinnedNote, { v -> update { it.copy(pinnedNote = v) } }, label = { Text(stringResource(R.string.detail_note_title)) },
                                placeholder = { Text(stringResource(R.string.detail_note_placeholder)) }, modifier = Modifier.fillMaxWidth(), minLines = 2, shape = FieldShape,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            )
                            Text(stringResource(R.string.edit_private_call_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                put("call:gap") { Spacer(Modifier.height(12.dp)) }
            }

            val lookup = original?.lookupKey
            if (!isVault && !lookup.isNullOrEmpty()) {
                put("bg") { Segment(SegPos.Single, Modifier.animateItem()) { Box(Modifier.padding(end = 8.dp, bottom = 8.dp)) { app.parley.ui.people.CallBackgroundEditor(vm, lookup, bgChange) { bgChange = it } } } }
                put("bg:gap") { Spacer(Modifier.height(12.dp)) }
            }

            // U5 / E1: "Add more info" offers only the kinds not on screen yet.
            if (EditorForm.addable(shownKinds).isNotEmpty()) {
                put("more") {
                    Box(Modifier.fillMaxWidth().animateItem(), contentAlignment = Alignment.Center) {
                        FilledTonalButton({ moreSheet = true }) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.v34e_more_info))
                        }
                    }
                }
            }
            put("end") { Spacer(Modifier.height(96.dp)) }
        }

        CompositionLocalProvider(LocalCountryIso provides vm.countryIso, LocalLocked provides original?.readOnlyDataIds.orEmpty()) {
            BoxWithConstraints(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
                val wide = maxWidth >= 720.dp
                if (wide) {
                    // E1: two columns on wide screens and in landscape: photo, account and name beside the fields.
                    Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { header() }
                        LazyColumn(Modifier.weight(0.58f).fillMaxHeight(), state = listState, contentPadding = PaddingValues(top = 8.dp)) { fields(0) }
                    }
                } else {
                    val side = ((maxWidth - 640.dp) / 2).coerceAtLeast(16.dp)
                    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = side)) {
                        item(key = "header") { Column(Modifier.padding(bottom = 16.dp)) { header() } }
                        fields(1)
                    }
                }
            }
        }

        if (moreSheet) {
            val entries = EditorForm.addable(shownKinds).map { k ->
                when (k) {
                    EditorForm.Kind.NAME_DETAILS -> MoreEntry(Icons.Rounded.Badge, stringResource(R.string.edit_name_details), stringResource(R.string.v34e_more_name)) { addKind(k) }
                    EditorForm.Kind.DATE -> MoreEntry(Icons.Rounded.Cake, stringResource(R.string.edit_important_dates), stringResource(R.string.v34e_more_date)) { addKind(k) }
                    EditorForm.Kind.ADDRESS -> MoreEntry(Icons.Rounded.Place, stringResource(R.string.detail_address), stringResource(R.string.v34e_more_address)) { addKind(k) }
                    EditorForm.Kind.WEBSITE -> MoreEntry(Icons.Rounded.Language, stringResource(R.string.detail_website), stringResource(R.string.v34e_more_website)) { addKind(k) }
                    EditorForm.Kind.HANDLE -> MoreEntry(Icons.Rounded.Forum, stringResource(R.string.edit_handles), stringResource(R.string.v34e_more_handle)) { addKind(k) }
                    EditorForm.Kind.RELATION -> MoreEntry(Icons.Rounded.People, stringResource(R.string.edit_relations), stringResource(R.string.v34e_more_relation)) { addKind(k) }
                    EditorForm.Kind.NOTE -> MoreEntry(Icons.AutoMirrored.Rounded.Notes, stringResource(R.string.edit_notes), stringResource(R.string.v34e_more_note)) { addKind(k) }
                }
            }
            MoreInfoSheet(entries) { moreSheet = false }
        }
    }

    askKeep?.let { (key, id) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.edit_keep_title)) },
            text = { Text(stringResource(R.string.edit_keep_body)) },
            confirmButton = { TextButton({ askKeep = null; scope.launch { vm.c.temporaries.answerKeep(key, true); done(id) } }) { Text(stringResource(R.string.edit_keep)) } },
            dismissButton = { TextButton({ askKeep = null; scope.launch { vm.c.temporaries.answerKeep(key, false); done(id) } }) { Text(stringResource(R.string.edit_still_delete)) } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.edit_discard_title)) },
            text = { Text(stringResource(R.string.v34e_discard_body)) },
            confirmButton = { TextButton({ confirmDiscard = false; done(null) }) { Text(stringResource(R.string.edit_discard)) } },
            dismissButton = { TextButton({ confirmDiscard = false }) { Text(stringResource(R.string.edit_keep_editing)) } },
        )
    }
}

/** Remembers which contact each relation names, by lookup key, beside the name-only Data row (F23, I5). */
private suspend fun rememberRelations(vm: AppViewModel, contactId: Long, e: ContactDetails, picked: Map<String, RelationLinks.Link>) = withContext(Dispatchers.IO) {
    val key = vm.c.contacts.lookupKeyOf(contactId) ?: return@withContext
    val m = vm.c.meta.meta(key)
    val existing = RelationLinks.decode(m?.relationLinks)
    val names = e.relations.map { it.value }.filter { it.isNotBlank() }
    if (names.isEmpty() && existing.isEmpty()) return@withContext
    val people = vm.c.contacts.snapshot().map { Triple(it.id, it.displayName, it.lookupKey) }
    val links = RelationLinks.update(names, existing, people, self = contactId, picked = picked)
    if (links == existing) return@withContext
    vm.c.meta.setMeta((m ?: app.parley.data.db.ContactMetaEntity(key)).copy(contactId = contactId, relationLinks = RelationLinks.encode(links).ifEmpty { null }))
}

/** E1: "Save to" chip for new contacts (private or an account), or where an existing contact lives. */
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
            vaultId != null -> InfoLine(Icons.Rounded.Lock, stringResource(R.string.v34e_private_here))
            isExisting -> InfoLine(
                if (account?.isLocal != false) Icons.Rounded.PhoneAndroid else Icons.Rounded.AccountCircle,
                stringResource(R.string.edit_saved_in, account?.displayLabel ?: stringResource(R.string.detail_phone)),
            )
            else -> {
                var open by remember { mutableStateOf(false) }
                val privateLabel = stringResource(R.string.edit_private_only)
                val current = if (privateNew) privateLabel else account?.let(label) ?: stringResource(R.string.edit_phone_only)
                val change = stringResource(R.string.v34e_change_account)
                Box {
                    AssistChip(
                        onClick = { open = true },
                        label = { Text(stringResource(R.string.v34e_saving_to, current), maxLines = 2) },
                        leadingIcon = {
                            Icon(
                                when { privateNew -> Icons.Rounded.Lock; account == null || account.isLocal -> Icons.Rounded.PhoneAndroid; else -> Icons.Rounded.AccountCircle },
                                null, Modifier.size(18.dp),
                            )
                        },
                        trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp)) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.semantics { onClick(label = change) { open = true; true } },
                    )
                    DropdownMenu(open, { open = false }) {
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
    Row(Modifier.semantics(mergeDescendants = true) {}.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** E1: the name card: first and last name, with a chevron for prefix, middle, suffix, phonetic and nickname. */
@Composable
private fun NameCard(
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
    val spec = spring<androidx.compose.ui.unit.IntSize>(stiffness = Spring.StiffnessMediumLow)
    Segment(SegPos.Single) {
        GroupHead(Icons.Rounded.Person, stringResource(R.string.edit_name))
        Column(Modifier.padding(end = 0.dp)) {
            AnimatedVisibility(expanded, enter = expandVertically(spec) + fadeIn(), exit = shrinkVertically(spec) + fadeOut()) {
                EditorField(stringResource(R.string.edit_prefix), d.prefix, Modifier.padding(end = 48.dp), cap = words, locked = locked) { v -> update { it.copy(prefix = v) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                EditorField(stringResource(R.string.edit_first_name), d.given, Modifier.weight(1f), cap = words, locked = locked, focus = first) { v -> update { it.copy(given = v) } }
                if (canCollapse || !expanded) {
                    IconButton(onToggle) {
                        Icon(
                            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            stringResource(if (expanded) R.string.v34e_name_less else R.string.v34e_name_more),
                        )
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }
            AnimatedVisibility(expanded, enter = expandVertically(spec) + fadeIn(), exit = shrinkVertically(spec) + fadeOut()) {
                EditorField(stringResource(R.string.edit_middle_name), d.middle, Modifier.padding(end = 48.dp), cap = words, locked = locked) { v -> update { it.copy(middle = v) } }
            }
            EditorField(stringResource(R.string.edit_last_name), d.family, Modifier.padding(end = 48.dp), cap = words, locked = locked) { v -> update { it.copy(family = v) } }
            AnimatedVisibility(expanded, enter = expandVertically(spec) + fadeIn(), exit = shrinkVertically(spec) + fadeOut()) {
                Column(Modifier.padding(end = 48.dp)) {
                    EditorField(stringResource(R.string.edit_suffix), d.suffix, cap = words, locked = locked) { v -> update { it.copy(suffix = v) } }
                    EditorField(stringResource(R.string.edit_phonetic_first), d.phoneticGiven, cap = words, locked = locked) { v -> update { it.copy(phoneticGiven = v) } }
                    EditorField(stringResource(R.string.edit_phonetic_last), d.phoneticFamily, cap = words, locked = locked) { v -> update { it.copy(phoneticFamily = v) } }
                    EditorField(stringResource(R.string.edit_nickname), d.nickname, cap = words, locked = lockedRow(d.nicknameId), focus = nick) { v -> update { it.copy(nickname = v) } }
                }
            }
            if (!expanded) {
                TextButton(onToggle) { Text(stringResource(R.string.edit_more_name)) }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** E1: one phone, e-mail or website: the field (flag for numbers), a type chip under it and the red "−". */
@Composable
private fun MultiRow(kind: MultiKind, item: DataItem, focus: FocusRequester, onChange: (DataItem) -> Unit, onRemove: () -> Unit) {
    val res = LocalResources.current
    val locked = item.id != null && item.id in LocalLocked.current
    val iso = LocalCountryIso.current
    val flag = if (kind === PHONES && item.value.length >= 6) remember(item.value, iso) { app.parley.data.NumberInfo.flag(app.parley.data.NumberInfo.region(item.value, iso)) } else null
    val current = if (item.type == 0) item.label ?: stringResource(R.string.edit_custom) else kind.typeLabel(res, item.type)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EditorField(
                stringResource(kind.title), item.value, Modifier.weight(1f), keyboard = kind.keyboard, locked = locked, focus = focus,
                prefix = flag?.let { "$it " },
                hint = kind.looksWrong?.takeIf { it(item.value) }?.let { stringResource(kind.hint) },
            ) { v -> onChange(item.copy(value = v)) }
            if (!locked) RemoveButton(stringResource(kind.remove), onRemove) else Spacer(Modifier.width(48.dp))
        }
        if (locked) {
            Text(current, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            var custom by remember { mutableStateOf(false) }
            TypeChip(current, kind.types.map { kind.typeLabel(res, it) } + stringResource(R.string.edit_custom_more)) { t ->
                if (t in kind.types.indices) onChange(item.copy(type = kind.types[t], label = null)) else custom = true
            }
            if (custom) CustomLabelDialog(item.label.takeIf { item.type == 0 }, { custom = false }) { l -> onChange(item.copy(type = 0, label = l)) }
        }
    }
}

/** E1: a date row: the year-optional picker behind a read-only field, and its type chip. */
@Composable
private fun DateRow(ev: EventItem, openPicker: Boolean, onPickerClosed: () -> Unit, onChange: (EventItem) -> Unit, onRemove: () -> Unit) {
    val res = LocalResources.current
    val locked = ev.id != null && ev.id in LocalLocked.current
    var picking by remember { mutableStateOf(false) }
    val pickLabel = stringResource(R.string.v34e_pick_date)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OutlinedTextField(
                    if (ev.date.isBlank()) "" else describeEvent(ev.date, false).substringBefore(" ·"), {}, readOnly = true,
                    label = { Text(stringResource(R.string.edit_date)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape,
                    trailingIcon = if (locked) { { LockIcon() } } else { { Icon(Icons.Rounded.Cake, null) } },
                )
                if (!locked) Box(Modifier.matchParentSize().clickable(onClickLabel = pickLabel) { picking = true })
            }
            if (!locked) RemoveButton(stringResource(R.string.edit_remove_date), onRemove) else Spacer(Modifier.width(48.dp))
        }
        if (!locked) {
            var custom by remember { mutableStateOf(false) }
            TypeChip(
                app.parley.ui.people.eventLabel(res, ev),
                eventTypes.map { res.getString(Event.getTypeResource(it)) } + stringResource(R.string.edit_event_death) + stringResource(R.string.edit_custom_more),
            ) { t ->
                when (t) {
                    in eventTypes.indices -> onChange(ev.copy(type = eventTypes[t], label = null))
                    eventTypes.size -> onChange(ev.copy(type = Event.TYPE_CUSTOM, label = LifeEvents.DEATH_LABEL))
                    else -> custom = true
                }
            }
            if (custom) CustomLabelDialog(ev.label.takeIf { ev.type == Event.TYPE_CUSTOM }, { custom = false }) { onChange(ev.copy(type = Event.TYPE_CUSTOM, label = it)) }
        } else {
            Text(app.parley.ui.people.eventLabel(res, ev), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
    if (picking || openPicker) {
        EventDateDialog(ev.date, onDismiss = { picking = false; onPickerClosed() }) { onChange(ev.copy(date = it)); picking = false; onPickerClosed() }
    }
}

/** E1: one address: type chip and "−" on top, then the parts (PO box and neighbourhood when it has them, F25). */
@Composable
private fun AddressRow(a: PostalItem, focus: FocusRequester, onChange: (PostalItem) -> Unit, onRemove: () -> Unit) {
    val res = LocalResources.current
    val locked = a.id != null && a.id in LocalLocked.current
    val words = KeyboardCapitalization.Words
    Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            var custom by remember { mutableStateOf(false) }
            val current = if (a.type == 0) a.label ?: stringResource(R.string.edit_custom) else StructuredPostal.getTypeLabel(res, a.type, a.label).toString()
            TypeChip(current, postalTypes.map { StructuredPostal.getTypeLabel(res, it, null).toString() } + stringResource(R.string.edit_custom_more), enabled = !locked) { t ->
                if (t in postalTypes.indices) onChange(a.copy(type = postalTypes[t], label = null)) else custom = true
            }
            if (custom) CustomLabelDialog(a.label.takeIf { a.type == 0 }, { custom = false }) { l -> onChange(a.copy(type = 0, label = l)) }
            Spacer(Modifier.weight(1f))
            if (!locked) RemoveButton(stringResource(R.string.edit_remove_address), onRemove)
        }
        EditorField(stringResource(R.string.edit_street), a.street, cap = words, locked = locked, focus = focus) { onChange(a.copy(street = it)) }
        if (a.poBox.isNotEmpty() || a.neighborhood.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorField(stringResource(R.string.edit_po_box), a.poBox, Modifier.weight(0.4f), locked = locked) { onChange(a.copy(poBox = it)) }
                EditorField(stringResource(R.string.edit_neighbourhood), a.neighborhood, Modifier.weight(0.6f), cap = words, locked = locked) { onChange(a.copy(neighborhood = it)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorField(stringResource(R.string.edit_postcode), a.postcode, Modifier.weight(0.4f), cap = KeyboardCapitalization.Characters, locked = locked) { onChange(a.copy(postcode = it)) }
            EditorField(stringResource(R.string.edit_city), a.city, Modifier.weight(0.6f), cap = words, locked = locked) { onChange(a.copy(city = it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorField(stringResource(R.string.edit_region), a.region, Modifier.weight(1f), cap = words, locked = locked) { onChange(a.copy(region = it)) }
            EditorField(stringResource(R.string.edit_country), a.country, Modifier.weight(1f), cap = words, locked = locked) { onChange(a.copy(country = it)) }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** I1 / E1: one messenger handle: service chip, the handle with a per-service hint, a warning when it looks off. */
@Composable
private fun HandleRow(h: HandleItem, focus: FocusRequester, onChange: (HandleItem) -> Unit, onRemove: () -> Unit) {
    val res = LocalResources.current
    val locked = h.id != null && h.id in LocalLocked.current
    val services = remember { HandleService.common + HandleService.entries.filter { it !in HandleService.common } }
    val problem = Handles.problem(h.service, h.value)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EditorField(
                HandleText.label(res, h.handle), h.value, Modifier.weight(1f), locked = locked, focus = focus,
                keyboard = if (h.service == HandleService.XMPP || h.service == HandleService.SIP) KeyboardType.Email else KeyboardType.Text,
                placeholder = h.service.placeholder.takeIf { it.isNotEmpty() },
                support = problem?.let { HandleText.problem(res, it) } ?: HandleText.hint(res, h.service), error = problem != null,
            ) { onChange(h.copy(value = it)) }
            if (!locked) RemoveButton(stringResource(R.string.edit_remove_handle), onRemove) else Spacer(Modifier.width(48.dp))
        }
        if (h.service == HandleService.OTHER && !locked) {
            EditorField(
                stringResource(R.string.edit_service_name), h.customProtocol.orEmpty(), Modifier.padding(end = 48.dp),
                placeholder = stringResource(R.string.edit_service_placeholder),
            ) { onChange(h.copy(customProtocol = it)) }
        }
        TypeChip(HandleText.service(res, h.service), services.map { HandleText.service(res, it) }, enabled = !locked) { t ->
            onChange(h.copy(service = services[t], customProtocol = if (services[t] == HandleService.OTHER) h.customProtocol else null))
        }
    }
}

/** I5 / E1: one relation: the name (or pick the contact), its searchable vCard 4.0 type as a chip. */
@Composable
private fun RelationRow(
    vm: AppViewModel,
    item: DataItem,
    focus: FocusRequester,
    onChange: (DataItem) -> Unit,
    onPicked: (String, RelationLinks.Link) -> Unit,
    onRemove: () -> Unit,
) {
    val res = LocalResources.current
    val locked = item.id != null && item.id in LocalLocked.current
    var typing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val label = RelationTypes.fromAndroid(item.type, item.label)?.let { app.parley.ui.people.RelationText.label(res, it) }
        ?: if (item.type == 0) item.label ?: stringResource(R.string.edit_custom) else Relation.getTypeLabel(res, item.type, null).toString()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EditorField(
                stringResource(R.string.edit_relation), item.value, Modifier.weight(1f), cap = KeyboardCapitalization.Words, locked = locked, focus = focus,
                trailing = { IconButton({ picking = true }) { Icon(Icons.Rounded.PersonSearch, stringResource(R.string.edit_choose_contact)) } },
            ) { v -> onChange(item.copy(value = v)) }
            if (!locked) RemoveButton(stringResource(R.string.edit_remove_relation), onRemove) else Spacer(Modifier.width(48.dp))
        }
        if (locked) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            // The relation types are many and searchable, so the chip opens the search dialog rather than a menu.
            val desc = stringResource(R.string.v34e_type, label)
            AssistChip(
                onClick = { typing = true }, label = { Text(label) },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp)) },
                shape = RoundedCornerShape(10.dp), modifier = Modifier.semantics { contentDescription = desc },
            )
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
