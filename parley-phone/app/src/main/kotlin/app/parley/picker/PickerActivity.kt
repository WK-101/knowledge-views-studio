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

    private fun deliver(picks: List<Pick>) {
        if (picks.isEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
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

    companion object {
        const val ACTION_JOIN_CONTACT = "com.android.contacts.action.JOIN_CONTACT"
        const val EXTRA_JOIN_TARGET = "com.android.contacts.action.CONTACT_ID"
    }
}

data class Pick(val contactId: Long, val uri: Uri, val title: String, val subtitle: String?, val photoUri: String?)
