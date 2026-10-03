package app.parley

import app.parley.security.SharedUris
import app.parley.security.LockedActivity
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.ContactsContract
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.parley.blocking.TemplateInbox
import app.parley.common.HomeLayout
import app.parley.common.calls.EmergencyPolicy
import app.parley.data.DataItem
import app.parley.data.EmergencyNumbers
import app.parley.security.AppLock
import app.parley.security.LockScreen
import app.parley.security.PinConfirmHost
import app.parley.ui.people.PrivateNameApprovalDialog
import app.parley.shortcuts.CircleWidget
import app.parley.shortcuts.FavoritesWidget
import app.parley.ui.blocking.BlockingDialog
import app.parley.ui.blocking.BlockingDialogs
import app.parley.ui.extras.SimpleInbox
import app.parley.ui.qr.QrInbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
            // Missed calls count as seen only once the list could actually be seen: never behind the lock.
            val showsUnlocked = settingsLoaded && !(locked && settings.appLock)
            LaunchedEffect(missedSeenPending, showsUnlocked) {
                if (missedSeenPending && showsUnlocked) {
                    missedSeenPending = false
                    vm.markMissedSeen()
                }
            }
            ParleyTheme(settings.themeMode, settings.amoledBlack, settings.dynamicColor, settings.density) {
                if (!settingsLoaded) {
                    // Behind the splash screen (kept until the settings load): nothing that could flash the contacts.
                } else if (locked && settings.appLock) {
                    LockScreen(lockEmergencyNumber, checkingEmergency) {
                        AppLock.authenticate(this@MainActivity) { ok -> if (ok) lockEmergencyNumber = null }
                    }
                } else {
                    ParleyRoot(vm)
                    PinConfirmHost()
                    // A private-name request's "Allow…": only once Parley shows unlocked.
                    approvePrivateName?.let { (pkg, dir) ->
                        PrivateNameApprovalDialog(vm.c.people.privateNames, pkg, dir) { approvePrivateName = null }
                    }
                }
            }
        }
    }

    /** Missed calls were opened from Parley's notification: marked seen once the screen shows unlocked. */
    private var missedSeenPending by mutableStateOf(false)

    /** A private-name request's "Allow…" waiting for the approval sheet: the package and whether it is the Directory. */
    private var approvePrivateName by mutableStateOf<Pair<String, Boolean>?>(null)

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
            if (s.appLock) launch { runCatching { FavoritesWidget.refreshIfShownLocked(applicationContext) } }
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

    /**
     * Another app's "Edit contact": resolves the URI off the main thread, then opens the editor. Like every link, it
     * waits behind the app lock (the screens only take navigation once unlocked).
     */
    private fun editResolved(uri: Uri) {
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) { vm.c.contacts.resolveContactId(uri) }
            if (id == null) {
                vm.toast(getString(R.string.main_edit_not_found))
                return@launch
            }
            vm.navigate(NavEvent.Route(IntentRoutes.editorFor(id, IntentRoutes.rawContactId(uri))))
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        checkEmergencyDial(intent)
        // Internal actions count only through Parley's own unexported entry; from any other app they open nothing.
        val t = IntentRoutes.resolve(intent, IntentRoutes.fromParley(intent), readable = { SharedUris.acceptable(this, it) }) { contentResolver.getType(it) }
            ?: return
        t.qrImage?.let { QrInbox.image.value = it }
        t.simpleSetup?.let { SimpleInbox.qr.value = it }
        t.template?.let { TemplateInbox.pending.value = it }
        t.resolveContact?.let(::openResolved)
        t.editContact?.let(::editResolved)
        t.showOrCreate?.let(::showOrCreate)
        t.report?.let { BlockingDialogs.show(BlockingDialog.Report(it)) }
        t.event?.let { vm.navigate(it) }
        if (t.missedSeen) missedSeenPending = true
        t.approvePrivateName?.let { approvePrivateName = it }
    }

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
        const val ACTION_ADD_CALL = IntentRoutes.ACTION_ADD_CALL
        const val ACTION_BULK_ADD = IntentRoutes.ACTION_BULK_ADD
        const val ACTION_OPEN_BACKUP = IntentRoutes.ACTION_OPEN_BACKUP
        const val ACTION_SCAN_QR = IntentRoutes.ACTION_SCAN_QR
        const val ACTION_OPEN_BLOCKING = IntentRoutes.ACTION_OPEN_BLOCKING
        const val ACTION_OPEN_SYNC = IntentRoutes.ACTION_OPEN_SYNC
        const val QUICK_CONTACT = IntentRoutes.QUICK_CONTACT
        const val QUICK_CONTACT_LEGACY = IntentRoutes.QUICK_CONTACT_LEGACY
        const val SHOW_OR_CREATE = IntentRoutes.SHOW_OR_CREATE
        const val ACTION_SHOW_MISSED = IntentRoutes.ACTION_SHOW_MISSED
        const val ACTION_SHOW_CIRCLE = IntentRoutes.ACTION_SHOW_CIRCLE
        const val ACTION_SHOW_CALLER = IntentRoutes.ACTION_SHOW_CALLER
        const val ACTION_POST_CALL = IntentRoutes.ACTION_POST_CALL
        const val EXTRA_POST_CALL_ACTION = IntentRoutes.EXTRA_POST_CALL_ACTION
        const val EXTRA_CONTACT_ID = IntentRoutes.EXTRA_CONTACT_ID
        const val EXTRA_NUMBER = IntentRoutes.EXTRA_NUMBER
    }
}
