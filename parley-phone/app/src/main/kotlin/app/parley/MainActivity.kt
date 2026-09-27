package app.parley

import app.parley.security.SharedUris
import app.parley.security.LockedActivity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.parley.blocking.TemplateInbox
import app.parley.common.AppSettings
import app.parley.common.HomeLayout
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.calls.EmergencyPolicy
import app.parley.data.DataItem
import app.parley.data.EmergencyNumbers
import app.parley.messaging.MessagingRoutes
import app.parley.security.AppLock
import app.parley.security.LockScreen
import app.parley.shortcuts.CircleWidget
import app.parley.ui.AppLocale
import app.parley.ui.Routes
import app.parley.ui.blocking.BlockingDialog
import app.parley.ui.blocking.BlockingDialogs
import app.parley.ui.blocking.BlockingRoutes
import app.parley.ui.extras.ExtrasRoutes
import app.parley.ui.extras.SimpleInbox
import app.parley.ui.qr.QrInbox
import app.parley.ui.qr.QrRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import app.parley.common.StartTab
import app.parley.ui.ParleyRoot
import app.parley.ui.ParleyTheme
import kotlinx.coroutines.withContext

/** The app itself. The app lock, window protection and locale come from [LockedActivity]. */
class MainActivity : LockedActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash stays up until the settings are read: whether the app lock is on decides what may show, so
        // there is no blank first frame and contacts never flash before the lock.
        installSplashScreen().setKeepOnScreenCondition { !container.settings.loaded.value }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The UI is starting: load contacts, calls and the rest (a process started for a call or a worker doesn't).
        container.startFull()
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
            val locked by AppLock.locked.collectAsStateWithLifecycle()
            val settingsLoaded by vm.c.settings.loaded.collectAsStateWithLifecycle()
            LaunchedEffect(settings.secureScreen, settings.appLock, locked, settingsLoaded) { protectWindow() }
            ParleyTheme(settings.themeMode, settings.amoledBlack, settings.dynamicColor, settings.density) {
                if (!settingsLoaded) {
                    // Behind the splash screen (kept until the settings load): nothing that could flash the contacts.
                } else if (locked && settings.appLock) {
                    LockScreen(lockEmergencyNumber, checkingEmergency) {
                        AppLock.authenticate(this@MainActivity) { ok -> if (ok) lockEmergencyNumber = null }
                    }
                } else {
                    ParleyRoot(vm)
                }
            }
        }
    }

    /** An emergency number handed over while Parley may be locked: the lock screen offers the call with it at once. */
    private var lockEmergencyNumber by mutableStateOf<String?>(null)
    private var checkingEmergency by mutableStateOf(false)

    /**
     * Android turns another app's emergency call into a dial request for the phone app. The number still goes to the
     * keypad as usual; the platform check runs off the main thread, and the lock screen's prompt waits for it.
     */
    private fun checkEmergencyDial(intent: Intent) {
        lockEmergencyNumber = null
        checkingEmergency = false
        if (intent.action != Intent.ACTION_DIAL && intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_CALL) return
        val number = intent.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
        checkingEmergency = true
        lifecycleScope.launch {
            val emergency = try {
                withContext(Dispatchers.IO) { EmergencyNumbers.isEmergency(applicationContext, number) }
            } finally {
                checkingEmergency = false
            }
            if (emergency) lockEmergencyNumber = EmergencyPolicy.asciiDigits(number)
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val s = vm.c.settings.current()
            // The phone is unlocked now: a Circle widget drawn while it was locked shows names again.
            if (s.appLock) launch { runCatching { CircleWidget.refreshIfShownLocked(applicationContext) } }
            // After a longer break, open on the preferred tab again; a quick app switch keeps your place.
            if (stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > 5 * 60_000L && intent?.action == Intent.ACTION_MAIN) {
                vm.navigate(NavEvent.Tab(HomeLayout(s.navTabs, s.surfaces).startRequest(s.startTab)))
            }
        }
    }

    private var stoppedAt = 0L

    override fun onStop() {
        stoppedAt = SystemClock.elapsedRealtime()
        // Placed or put aside: coming back shows the ordinary lock screen.
        lockEmergencyNumber = null
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
    private fun openResolved(uri: Uri) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { vm.c.contacts.resolveContactId(uri) }?.let { vm.navigate(NavEvent.Contact(it)) }
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        checkEmergencyDial(intent)
        val data = intent.data
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.takeIf { SharedUris.acceptable(this, it) }
                if (stream != null && isVcard(intent.type)) vm.navigate(NavEvent.ImportVcf(stream))
                // A picture shared to Parley is searched for QR codes.
                if (stream != null && intent.type?.startsWith("image/") == true) {
                    QrInbox.image.value = stream
                    vm.navigate(NavEvent.Route(QrRoutes.SCAN))
                }
            }
            QUICK_CONTACT, QUICK_CONTACT_LEGACY -> data?.let(::openResolved)
            SHOW_OR_CREATE -> showOrCreate(data)
            Intent.ACTION_DIAL, Intent.ACTION_VIEW -> when {
                data?.scheme == "parley" && data.host == "qr" -> vm.navigate(NavEvent.SecureQr(data))
                // A simple-mode setup shared as a QR code.
                data?.scheme == "parley" && data.host == "simple" -> {
                    SimpleInbox.qr.value = data
                    vm.navigate(NavEvent.Route(ExtrasRoutes.SIMPLE_IMPORT))
                }
                data?.scheme == "parley" && data.host == "template" -> {
                    TemplateInbox.pending.value = data
                    vm.navigate(NavEvent.Route(BlockingRoutes.TEMPLATES))
                }
                data != null && SharedUris.acceptable(this, data) && isVcard(intent.type ?: contentResolver.getType(data)) -> vm.navigate(NavEvent.ImportVcf(data))
                data?.scheme == "tel" -> vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = data.schemeSpecificPart.orEmpty()))
                intent.type == "vnd.android.cursor.dir/calls" -> vm.navigate(NavEvent.Tab(StartTab.RECENTS))
                intent.action == Intent.ACTION_DIAL -> vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = ""))
                data != null -> openResolved(data)
            }
            Intent.ACTION_CALL_BUTTON -> vm.navigate(NavEvent.Tab(StartTab.RECENTS))
            Intent.ACTION_APPLICATION_PREFERENCES -> vm.navigate(NavEvent.Route(Routes.SETTINGS))
            ACTION_OPEN_BACKUP ->vm.navigate(NavEvent.Route(Routes.BACKUP))
            ACTION_OPEN_BLOCKING -> vm.navigate(NavEvent.Route(Routes.BLOCKING))
            ACTION_ADD_CALL -> vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = ""))
            ACTION_BULK_ADD -> vm.navigate(NavEvent.Route(MessagingRoutes.BULK_ADD))
            // Launcher shortcut and Quick Settings tile.
            ACTION_SCAN_QR -> vm.navigate(NavEvent.Route(QrRoutes.SCAN))
            // The keep-in-touch digest opens the Circle (as the bar's extra tab while it's hidden).
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
            // The post-call card's "Block" and "Report" for an unknown number.
            ACTION_POST_CALL -> intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() }?.let { number ->
                when (intent.getStringExtra(EXTRA_POST_CALL_ACTION)) {
                    "BLOCK" -> vm.navigate(
                        NavEvent.Route(BlockingRoutes.rule(0, RuleKind.BLOCK, RuleType.EXACT, number)),
                    )
                    "REPORT" -> BlockingDialogs.show(BlockingDialog.Report(number))
                }
            }
            Intent.ACTION_INSERT -> vm.navigate(NavEvent.NewContact(InsertPrefill.from(intent)))
            Intent.ACTION_INSERT_OR_EDIT -> vm.navigate(NavEvent.InsertOrEdit(InsertPrefill.from(intent)))
        }
    }

    private fun isVcard(type: String?) = type != null && (type.contains("vcard") || type == "text/directory")

    /** SHOW_OR_CREATE_CONTACT: open the matching contact, or offer to create one. */
    private fun showOrCreate(data: Uri?) {
        data ?: return
        val intent = intent
        lifecycleScope.launch { showOrCreate(data, intent) }
    }

    /** The provider lookups run off the main thread; navigation happens back on it. */
    private suspend fun showOrCreate(data: Uri, intent: Intent) {
        val value = data.schemeSpecificPart.orEmpty()
        val id: Long? = withContext(Dispatchers.IO) {
            when (data.scheme) {
                "tel" -> runCatching { vm.c.contacts.lookup(value)?.contactId }.getOrNull()
                "mailto" -> runCatching {
                    contentResolver.query(
                        Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI, Uri.encode(value)),
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
                if (data.scheme == "tel") p.copy(phones = listOf(DataItem(value = value, type = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)))
                else p.copy(emails = listOf(DataItem(value = value, type = ContactsContract.CommonDataKinds.Email.TYPE_HOME)))
            }
            vm.navigate(NavEvent.InsertOrEdit(prefill))
        }
    }

    companion object {
        const val ACTION_ADD_CALL = "app.parley.ADD_CALL"
        /** "Save all…" from the number sheet; the text waits in [app.parley.messaging.MessagingInbox]. */
        const val ACTION_BULK_ADD = "app.parley.BULK_ADD"
        const val ACTION_OPEN_BACKUP = "app.parley.OPEN_BACKUP"
        /** Opens the Scan QR screen (launcher shortcut, Quick Settings tile). */
        const val ACTION_SCAN_QR = "app.parley.action.SCAN_QR"
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
