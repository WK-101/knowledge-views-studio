package app.parley.picker

import app.parley.security.LockedActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.common.PhoneIdentity
import android.app.Activity
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.parley.container
import app.parley.security.AppLock
import app.parley.security.LockScreen
import app.parley.ui.DataL10n
import app.parley.ui.ParleyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.parley.ui.common.ProvideAppKit
import app.parley.ui.ConfirmDialog

/** What another app asked us to pick. */
enum class PickKind(val mime: String) {
    CONTACT(ContactsContract.Contacts.CONTENT_ITEM_TYPE),
    PHONE(Phone.CONTENT_ITEM_TYPE),
    EMAIL(Email.CONTENT_ITEM_TYPE),
    POSTAL(StructuredPostal.CONTENT_ITEM_TYPE),
    ;

    companion object {
        fun from(intent: Intent): PickKind {
            val type = intent.type.orEmpty()
            val data = intent.data?.toString().orEmpty()
            return when {
                type.contains("phone") || data.contains("/phones") -> PHONE
                type.contains("email") || data.contains("/emails") -> EMAIL
                type.contains("postal") || data.contains("/postals") -> POSTAL
                else -> CONTACT
            }
        }
    }
}

/**
 * Serves ACTION_PICK / ACTION_GET_CONTENT for contacts, phone numbers, e-mails and addresses so
 * other apps can use Parley as the system contact picker. Also handles JOIN_CONTACT.
 * Returns aggregate contact lookup URIs or Data row URIs, with a read grant.
 */
class PickerActivity : LockedActivity() {
    // It hands contacts to another app: other apps' overlays can't cover the choice.
    override val hidesOverlays = true

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val kind = PickKind.from(intent)
        val multiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        val joinTarget = if (intent.action == ACTION_JOIN_CONTACT) intent.getLongExtra(EXTRA_JOIN_TARGET, -1L).takeIf { it > 0 } else null
        val c = container
        // Another app chose the contact to join: its name is shown, and the join waits for an explicit confirmation.
        if (joinTarget != null) lifecycleScope.launch { joinTargetName = withContext(Dispatchers.IO) { nameOf(joinTarget) } }
        setContent {
            val s by c.settings.settings.collectAsStateWithLifecycle()
            val locked by AppLock.locked.collectAsStateWithLifecycle()
            LaunchedEffect(s.secureScreen) { AppLock.applySecureFlag(this@PickerActivity, s.secureScreen) }
            val loaded by c.settings.loaded.collectAsStateWithLifecycle()
            ParleyTheme(s.themeMode, s.amoledBlack, s.dynamicColor, s.density) {
                if (!loaded) return@ParleyTheme
                if (locked && s.appLock) {
                    LockScreen { AppLock.authenticate(this@PickerActivity) }
                    return@ParleyTheme
                }
                if (joinTarget != null) JoinConfirm(joinTarget)
                oneField?.let { (pick, phones) ->
                    OneFieldDialog(pick, phones, onWhole = { oneField = null; deliver(listOf(pick), ask = false) }, onNumber = { uri ->
                        oneField = null
                        deliver(listOf(pick.copy(uri = uri)), ask = false)
                    }, onDismiss = { oneField = null })
                }
                ProvideAppKit {
                    PickerScreen(
                        kind = if (joinTarget != null) PickKind.CONTACT else kind,
                        multiple = multiple && joinTarget == null,
                        title = joinTarget?.let { joinTitle() },
                        excludeContactId = joinTarget,
                        onCancel = { setResult(Activity.RESULT_CANCELED); finish() },
                        onPicked = { picks -> if (joinTarget != null) joinConfirm = picks.firstOrNull() else deliver(picks) },
                    )
                }
            }
        }
    }

    /** "Link these contacts?", naming the asking app and both contacts, before another app's join goes ahead. */
    @Composable
    private fun JoinConfirm(target: Long) {
        val other = joinConfirm ?: return
        ConfirmDialog(
            title = stringResource(R.string.picker_join_title),
            text = stringResource(R.string.picker_join_text, callerLabel(), joinTargetName.orEmpty(), other.title),
            confirmLabel = stringResource(R.string.picker_join_confirm),
            onConfirm = { joinConfirm = null; join(target, listOf(other)) },
            onDismiss = { joinConfirm = null },
            dismissLabel = stringResource(R.string.dc_cancel),
        )
    }

    private var joinTargetName by mutableStateOf<String?>(null)
    private var joinConfirm by mutableStateOf<Pick?>(null)

    private fun nameOf(contactId: Long): String? = runCatching {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        contentResolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun joinTitle(): String = joinTargetName?.let { getString(R.string.picker_link_name_with, it) } ?: getString(R.string.picker_link_with)

    /** The app that asked, by its label (the package name when it has none). */
    private fun callerLabel(): String {
        val pkg = callingActivity?.packageName ?: return getString(R.string.picker_join_another_app)
        return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun join(target: Long, picks: List<Pick>) {
        val other = picks.firstOrNull() ?: return
        lifecycleScope.launch {
            container.contacts.join(listOf(target, other.contactId))
            setResult(Activity.RESULT_OK, Intent().setData(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, target)))
            finish()
        }
    }

    /** Pending "share the whole contact or only one number?" question (Privacy › Share just one contact). */
    private var oneField by mutableStateOf<Pair<Pick, List<Pair<String, Uri>>>?>(null)

    private fun deliver(picks: List<Pick>, ask: Boolean = true) {
        if (picks.isEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val single = picks.singleOrNull()
        if (ask && single != null && PickKind.from(intent) == PickKind.CONTACT && intent.action != ACTION_JOIN_CONTACT &&
            container.people.prefs.settings.value.pickerOneField
        ) {
            lifecycleScope.launch {
                val phones = withContext(Dispatchers.IO) { phonesOf(single.contactId) }
                if (phones.isEmpty()) deliver(picks, ask = false) else oneField = single to phones
            }
            return
        }
        val uris = picks.map { it.uri }
        val result = Intent().setData(uris.first()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size > 1) {
            val clip = ClipData.newRawUri(null, uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            result.clipData = clip
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    /** The contact's numbers as Data row URIs: an app given one of them can read that number and the name only. */
    private fun phonesOf(contactId: Long): List<Pair<String, Uri>> = try {
        contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone._ID, Phone.NUMBER), "${Phone.CONTACT_ID}=?", arrayOf(contactId.toString()), null,
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getString(1).orEmpty() to ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, c.getLong(0))) } }
            .orEmpty().distinctBy { PhoneIdentity.key(it.first, null) }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        const val ACTION_JOIN_CONTACT = "com.android.contacts.action.JOIN_CONTACT"
        const val EXTRA_JOIN_TARGET = "com.android.contacts.action.CONTACT_ID"
    }
}

data class Pick(val contactId: Long, val uri: Uri, val title: String, val subtitle: String?, val photoUri: String?)

@Composable
private fun OneFieldDialog(pick: Pick, phones: List<Pair<String, Uri>>, onWhole: () -> Unit, onNumber: (Uri) -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.picker_share_title, pick.title),
        text = null,
        confirmLabel = stringResource(R.string.picker_whole),
        onConfirm = onWhole,
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.dc_cancel),
        content = {
            Column {
                Text(stringResource(R.string.picker_share_one))
                phones.forEach { (n, uri) ->
                    TextButton({ onNumber(uri) }) { Text(stringResource(R.string.picker_only, DataL10n.ltr(n))) }
                }
            }
        },
    )
}
