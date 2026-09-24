package app.parley

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.common.StartTab
import app.parley.ui.ParleyRoot
import app.parley.ui.ParleyTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (!granted) vm.toast("Phone permission is needed to place calls")
            }
            LaunchedEffect(Unit) {
                vm.uiEvents.collect { e ->
                    if (e is UiEvent.RequestCallPermission) callPermission.launch(Manifest.permission.CALL_PHONE)
                }
            }
            ParleyTheme(settings.themeMode, settings.amoledBlack, settings.dynamicColor, settings.density) {
                ParleyRoot(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshEnvironment()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val data = intent.data
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                if (stream != null && isVcard(intent.type)) vm.navigate(NavEvent.ImportVcf(stream))
            }
            QUICK_CONTACT, QUICK_CONTACT_LEGACY -> data?.let { vm.c.contacts.resolveContactId(it) }?.let { vm.navigate(NavEvent.Contact(it)) }
            SHOW_OR_CREATE -> showOrCreate(data)
            Intent.ACTION_DIAL, Intent.ACTION_VIEW -> when {
                data != null && isVcard(intent.type ?: contentResolver.getType(data)) -> vm.navigate(NavEvent.ImportVcf(data))
                data?.scheme == "tel" -> vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = data.schemeSpecificPart.orEmpty()))
                intent.type == "vnd.android.cursor.dir/calls" -> vm.navigate(NavEvent.Tab(StartTab.RECENTS))
                intent.action == Intent.ACTION_DIAL -> vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = ""))
                data != null -> vm.c.contacts.resolveContactId(data)?.let { vm.navigate(NavEvent.Contact(it)) }
            }
            Intent.ACTION_CALL_BUTTON -> vm.navigate(NavEvent.Tab(StartTab.RECENTS))
            ACTION_ADD_CALL -> vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = ""))
            ACTION_SHOW_MISSED -> {
                vm.navigate(NavEvent.Tab(StartTab.RECENTS, missedOnly = true))
                vm.markMissedSeen()
            }
            ACTION_SHOW_CALLER -> {
                val id = intent.getLongExtra(EXTRA_CONTACT_ID, -1)
                val number = intent.getStringExtra(EXTRA_NUMBER)
                when {
                    id > 0 -> vm.navigate(NavEvent.Contact(id))
                    !number.isNullOrBlank() -> vm.navigate(NavEvent.History(number))
                }
            }
            Intent.ACTION_INSERT -> vm.navigate(NavEvent.NewContact(InsertPrefill.from(intent)))
            Intent.ACTION_INSERT_OR_EDIT -> vm.navigate(NavEvent.InsertOrEdit(InsertPrefill.from(intent)))
        }
    }

    private fun isVcard(type: String?) = type != null && (type.contains("vcard") || type == "text/directory")

    /** SHOW_OR_CREATE_CONTACT: open the matching contact, or offer to create one. */
    private fun showOrCreate(data: android.net.Uri?) {
        data ?: return
        val value = data.schemeSpecificPart.orEmpty()
        val id: Long? = when (data.scheme) {
            "tel" -> vm.c.contacts.lookup(value)?.contactId
            "mailto" -> runCatching {
                contentResolver.query(
                    android.net.Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI, android.net.Uri.encode(value)),
                    arrayOf(ContactsContract.Data.CONTACT_ID), null, null, null,
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            }.getOrNull()
            else -> null
        }
        if (id != null) {
            vm.navigate(NavEvent.Contact(id))
        } else {
            val prefill = InsertPrefill.from(intent).let { p ->
                if (data.scheme == "tel") p.copy(phones = listOf(app.parley.data.DataItem(value = value, type = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)))
                else p.copy(emails = listOf(app.parley.data.DataItem(value = value, type = ContactsContract.CommonDataKinds.Email.TYPE_HOME)))
            }
            vm.navigate(NavEvent.InsertOrEdit(prefill))
        }
    }

    companion object {
        const val ACTION_ADD_CALL = "app.parley.ADD_CALL"
        const val QUICK_CONTACT = "android.provider.action.QUICK_CONTACT"
        const val QUICK_CONTACT_LEGACY = "com.android.contacts.action.QUICK_CONTACT"
        const val SHOW_OR_CREATE = "com.android.contacts.action.SHOW_OR_CREATE_CONTACT"
        const val ACTION_SHOW_MISSED = "app.parley.SHOW_MISSED"
        const val ACTION_SHOW_CALLER = "app.parley.SHOW_CALLER"
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_NUMBER = "number"
    }
}
