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
    // L1: the in-app language on Android 10-12 (Android 13+ applies per-app languages itself).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        app.parley.ui.AppLocale.override(this, newBase)
    }

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (!granted) vm.toast(getString(R.string.main_call_permission_needed))
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
            // R7: the phone is unlocked now: a Circle widget drawn while it was locked shows names again.
            if (s.appLock) launch { runCatching { app.parley.shortcuts.CircleWidget.refreshIfShownLocked(applicationContext) } }
            // After a longer break, open on the preferred tab again; a quick app switch keeps your place.
            if (stoppedAt > 0 && android.os.SystemClock.elapsedRealtime() - stoppedAt > 5 * 60_000L && intent?.action == android.content.Intent.ACTION_MAIN) {
                vm.navigate(NavEvent.Tab(s.navTabs.startTab(s.startTab)))
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
                // X4: a simple-mode setup shared as a QR code.
                data?.scheme == "parley" && data.host == "simple" -> {
                    app.parley.ui.extras.SimpleInbox.qr.value = data
                    vm.navigate(NavEvent.Route(app.parley.ui.extras.ExtrasRoutes.SIMPLE_IMPORT))
                }
                data?.scheme == "parley" && data.host == "template" -> {
                    app.parley.blocking.TemplateInbox.pending.value = data
                    vm.navigate(NavEvent.Route(app.parley.ui.blocking.BlockingRoutes.TEMPLATES))
                }
                data != null && data.scheme == "content" && isVcard(intent.type ?: contentResolver.getType(data)) -> vm.navigate(NavEvent.ImportVcf(data))
                data?.scheme == "tel" -> vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = data.schemeSpecificPart.orEmpty()))
                intent.type == "vnd.android.cursor.dir/calls" -> vm.navigate(NavEvent.Tab(StartTab.RECENTS))
                intent.action == Intent.ACTION_DIAL -> vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = ""))
                data != null -> openResolved(data)
            }
            Intent.ACTION_CALL_BUTTON -> vm.navigate(NavEvent.Tab(StartTab.RECENTS))
            Intent.ACTION_APPLICATION_PREFERENCES -> vm.navigate(NavEvent.Route(app.parley.ui.Routes.SETTINGS))
            ACTION_OPEN_BACKUP ->vm.navigate(NavEvent.Route(app.parley.ui.Routes.BACKUP))
            ACTION_OPEN_BLOCKING -> vm.navigate(NavEvent.Route(app.parley.ui.Routes.BLOCKING))
            ACTION_ADD_CALL -> vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = ""))
            ACTION_BULK_ADD -> vm.navigate(NavEvent.Route(app.parley.messaging.MessagingRoutes.BULK_ADD))
            // R4: the keep-in-touch digest opens the Circle (as the bar's extra tab while it's hidden).
            ACTION_SHOW_CIRCLE -> vm.navigate(NavEvent.Tab(StartTab.CIRCLE))
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
            // V4: the post-call card's "Block" and "Report" for an unknown number.
            ACTION_POST_CALL -> intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() }?.let { number ->
                when (intent.getStringExtra(EXTRA_POST_CALL_ACTION)) {
                    "BLOCK" -> vm.navigate(
                        NavEvent.Route(app.parley.ui.blocking.BlockingRoutes.rule(0, app.parley.common.RuleKind.BLOCK, app.parley.common.RuleType.EXACT, number)),
                    )
                    "REPORT" -> app.parley.ui.blocking.BlockingDialogs.show(app.parley.ui.blocking.BlockingDialog.Report(number))
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
        val intent = intent
        lifecycleScope.launch { showOrCreate(data, intent) }
    }

    /** The provider lookups run off the main thread; navigation happens back on it. */
    private suspend fun showOrCreate(data: android.net.Uri, intent: Intent) {
        val value = data.schemeSpecificPart.orEmpty()
        val id: Long? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (data.scheme) {
                "tel" -> runCatching { vm.c.contacts.lookup(value)?.contactId }.getOrNull()
                "mailto" -> runCatching {
                    contentResolver.query(
                        android.net.Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI, android.net.Uri.encode(value)),
                        arrayOf(ContactsContract.Data.CONTACT_ID), null, null, null,
                    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                }.getOrNull()
                else -> null
            }
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
        /** M11: "Save all…" from the number sheet; the text waits in [app.parley.messaging.MessagingInbox]. */
        const val ACTION_BULK_ADD = "app.parley.BULK_ADD"
        const val ACTION_OPEN_BACKUP = "app.parley.OPEN_BACKUP"
        const val ACTION_OPEN_BLOCKING = "app.parley.OPEN_BLOCKING"
        const val QUICK_CONTACT = "android.provider.action.QUICK_CONTACT"
        const val QUICK_CONTACT_LEGACY = "com.android.contacts.action.QUICK_CONTACT"
        const val SHOW_OR_CREATE = "com.android.contacts.action.SHOW_OR_CREATE_CONTACT"
        const val ACTION_SHOW_MISSED = "app.parley.SHOW_MISSED"
        const val ACTION_SHOW_CIRCLE = "app.parley.SHOW_CIRCLE"
        const val ACTION_SHOW_CALLER = "app.parley.SHOW_CALLER"
        const val ACTION_POST_CALL = "app.parley.POST_CALL"
        const val EXTRA_POST_CALL_ACTION = "post_call_action"
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_NUMBER = "number"
    }
}
