package app.parley

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.parley.common.StartTab
import app.parley.ui.ParleyRoot
import app.parley.ui.ParleyTheme

class MainActivity : androidx.fragment.app.FragmentActivity() {
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
            val locked by app.parley.security.AppLock.locked.collectAsStateWithLifecycle()
            LaunchedEffect(settings.secureScreen) { app.parley.security.AppLock.applySecureFlag(this@MainActivity, settings.secureScreen) }
            ParleyTheme(settings.themeMode, settings.amoledBlack, settings.dynamicColor, settings.density) {
                val settingsLoaded by vm.c.settings.loaded.collectAsStateWithLifecycle()
                if (!settingsLoaded) {
                    // Until we know whether the app lock is on, show nothing rather than flash the contacts.
                    androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {}
                } else if (locked && settings.appLock) {
                    app.parley.security.LockScreen { app.parley.security.AppLock.authenticate(this@MainActivity) }
                } else {
                    ParleyRoot(vm)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val s = vm.c.settings.current()
            app.parley.security.AppLock.onStart(s)
            // After a longer break, open on the preferred tab again; a quick app switch keeps your place.
            if (stoppedAt > 0 && android.os.SystemClock.elapsedRealtime() - stoppedAt > 5 * 60_000L && intent?.action == android.content.Intent.ACTION_MAIN) {
                vm.navigate(NavEvent.Tab(s.startTab))
            }
        }
    }

    private var stoppedAt = 0L

    override fun onStop() {
        stoppedAt = android.os.SystemClock.elapsedRealtime()
        app.parley.security.AppLock.onStop()
        super.onStop()
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

    /** Resolves a contacts URI off the main thread (it queries the provider), then opens the contact. */
    private fun openResolved(uri: android.net.Uri) {
        lifecycleScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.c.contacts.resolveContactId(uri) }?.let { vm.navigate(NavEvent.Contact(it)) }
        }
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
            QUICK_CONTACT, QUICK_CONTACT_LEGACY -> data?.let(::openResolved)
            SHOW_OR_CREATE -> showOrCreate(data)
            Intent.ACTION_DIAL, Intent.ACTION_VIEW -> when {
                data?.scheme == "parley" && data.host == "qr" -> vm.navigate(NavEvent.SecureQr(data))
                data != null && data.scheme == "content" && isVcard(intent.type ?: contentResolver.getType(data)) -> vm.navigate(NavEvent.ImportVcf(data))
                data?.scheme == "tel" -> vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = data.schemeSpecificPart.orEmpty()))
                intent.type == "vnd.android.cursor.dir/calls" -> vm.navigate(NavEvent.Tab(StartTab.RECENTS))
                intent.action == Intent.ACTION_DIAL -> vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = ""))
                data != null -> openResolved(data)
            }
            Intent.ACTION_CALL_BUTTON -> vm.navigate(NavEvent.Tab(StartTab.RECENTS))
            ACTION_OPEN_BACKUP -> vm.navigate(NavEvent.Route(app.parley.ui.Routes.BACKUP))
            ACTION_OPEN_BLOCKING -> vm.navigate(NavEvent.Route(app.parley.ui.Routes.BLOCKING))
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
        const val ACTION_OPEN_BACKUP = "app.parley.OPEN_BACKUP"
        const val ACTION_OPEN_BLOCKING = "app.parley.OPEN_BLOCKING"
        const val QUICK_CONTACT = "android.provider.action.QUICK_CONTACT"
        const val QUICK_CONTACT_LEGACY = "com.android.contacts.action.QUICK_CONTACT"
        const val SHOW_OR_CREATE = "com.android.contacts.action.SHOW_OR_CREATE_CONTACT"
        const val ACTION_SHOW_MISSED = "app.parley.SHOW_MISSED"
        const val ACTION_SHOW_CALLER = "app.parley.SHOW_CALLER"
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_NUMBER = "number"
    }
}
