package app.parley.picker

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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.parley.container
import app.parley.ui.ParleyTheme
import kotlinx.coroutines.launch

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
class PickerActivity : androidx.fragment.app.FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val kind = PickKind.from(intent)
        val multiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        val joinTarget = if (intent.action == ACTION_JOIN_CONTACT) intent.getLongExtra(EXTRA_JOIN_TARGET, -1L).takeIf { it > 0 } else null
        val c = container
        setContent {
            val s by c.settings.settings.collectAsStateWithLifecycle()
            val locked by app.parley.security.AppLock.locked.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(s.secureScreen) { app.parley.security.AppLock.applySecureFlag(this@PickerActivity, s.secureScreen) }
            val loaded by c.settings.loaded.collectAsStateWithLifecycle()
            ParleyTheme(s.themeMode, s.amoledBlack, s.dynamicColor, s.density) {
                if (!loaded) return@ParleyTheme
                if (locked && s.appLock) {
                    app.parley.security.LockScreen { app.parley.security.AppLock.authenticate(this@PickerActivity) }
                    return@ParleyTheme
                }
                oneField?.let { (pick, phones) ->
                    OneFieldDialog(pick, phones, onWhole = { oneField = null; deliver(listOf(pick), ask = false) }, onNumber = { uri ->
                        oneField = null
                        deliver(listOf(pick.copy(uri = uri)), ask = false)
                    }, onDismiss = { oneField = null })
                }
                PickerScreen(
                    kind = if (joinTarget != null) PickKind.CONTACT else kind,
                    multiple = multiple && joinTarget == null,
                    title = if (joinTarget != null) "Link with…" else null,
                    excludeContactId = joinTarget,
                    onCancel = { setResult(Activity.RESULT_CANCELED); finish() },
                    onPicked = { picks -> if (joinTarget != null) join(joinTarget, picks) else deliver(picks) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { app.parley.security.AppLock.onStart(container.settings.current()) }
    }

    override fun onStop() {
        app.parley.security.AppLock.onStop()
        super.onStop()
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
    private var oneField by androidx.compose.runtime.mutableStateOf<Pair<Pick, List<Pair<String, Uri>>>?>(null)

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
                val phones = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { phonesOf(single.contactId) }
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
            .orEmpty().distinctBy { app.parley.common.PhoneNumbers.matchKey(it.first) }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        const val ACTION_JOIN_CONTACT = "com.android.contacts.action.JOIN_CONTACT"
        const val EXTRA_JOIN_TARGET = "com.android.contacts.action.CONTACT_ID"
    }
}

data class Pick(val contactId: Long, val uri: Uri, val title: String, val subtitle: String?, val photoUri: String?)

@androidx.compose.runtime.Composable
private fun OneFieldDialog(pick: Pick, phones: List<Pair<String, Uri>>, onWhole: () -> Unit, onNumber: (Uri) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("Share ${pick.title}") },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text("Share only one number instead of the whole contact? Some apps may not accept a single number.")
                phones.forEach { (n, uri) ->
                    androidx.compose.material3.TextButton({ onNumber(uri) }) { androidx.compose.material3.Text("Only $n") }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onWhole) { androidx.compose.material3.Text("Whole contact") } },
        dismissButton = { androidx.compose.material3.TextButton(onDismiss) { androidx.compose.material3.Text("Cancel") } },
    )
}
