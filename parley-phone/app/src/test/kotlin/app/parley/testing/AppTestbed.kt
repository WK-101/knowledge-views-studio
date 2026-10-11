package app.parley.testing

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.parley.common.AppSettings
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeCallLogProvider
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf

/**
 * A data graph over Robolectric's fake contacts and call-log providers, for view-model tests: the real repositories
 * and stores run unchanged, with the permissions granted and the full start opened.
 */
class AppTestbed {
    val context: Application = ApplicationProvider.getApplicationContext()
    val contacts: FakeContactsProvider = FakeAndroidKeyStore.install().let { FakeContactsProvider.install() }
    val callLog: FakeCallLogProvider = FakeCallLogProvider.install()
    val c: DataContainer

    /** Collectors on the main looper, which keep the view models' WhileSubscribed flows running. */
    val ui = CoroutineScope(Dispatchers.Main)

    init {
        shadowOf(context).grantPermissions(
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG,
        )
        VaultCrypto.appContext = context
        c = DataContainer(context)
        // Start from defaults, whatever an earlier test stored.
        runBlocking { c.settings.update { AppSettings() } }
    }

    private var started = false

    /**
     * Opens the full start (the contact list and the call log load from here on). The fake providers announce no
     * changes, so a test adds its contacts and calls first; [viewModel] starts it. Later changes need [refresh].
     */
    fun start() {
        if (started) return
        started = true
        c.startFull()
    }

    /** Reads the contacts and the call log again after a change made once they had loaded. */
    fun refresh() {
        c.contacts.refresh()
        c.callLog.refresh()
    }

    private val viewModels = ViewModelStore()

    /**
     * A view model owned by this test bed, cleared by [close]: its scope must not outlive the test, or its work would
     * reach the next test's providers.
     */
    inline fun <reified T : ViewModel> viewModel(noinline create: () -> T): T = viewModel(T::class.java, create)

    fun <T : ViewModel> viewModel(type: Class<T>, create: () -> T): T {
        val factory = object : ViewModelProvider.Factory {
            override fun <V : ViewModel> create(modelClass: Class<V>): V = type.cast(create()).let { modelClass.cast(it)!! }
        }
        start()
        return ViewModelProvider(viewModels, factory)["${type.name}#${++made}", type]
    }

    private var made = 0

    fun close() {
        viewModels.clear()
        ui.cancel()
        c.scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Keeps [flow] collected for the rest of the test. */
    fun <T> keep(flow: Flow<T>) {
        ui.launch { flow.collect { } }
    }

    /** Runs the main looper until [check] holds (10 s at most). */
    fun until(what: String, check: () -> Boolean) = until({ what }, check)

    /** [until], with a description worked out when it times out (what was there instead). */
    fun until(what: () -> String, check: () -> Boolean) = awaitMain(what, check = check)

    /** A device contact with [numbers]; returns its contact id. */
    fun contact(given: String, family: String = "", vararg numbers: String): Long = runBlocking {
        val details = ContactDetails(given = given, family = family, phones = numbers.map { DataItem(null, it, Phone.TYPE_MOBILE) })
        c.contacts.save(null, details, null, null, false)!!.contactId
    }

    /** A private contact with [numbers]; returns its vault id. */
    fun privateContact(given: String, vararg numbers: String): Long = runBlocking {
        c.vault.save(null, ContactDetails(given = given, phones = numbers.map { DataItem(null, it, Phone.TYPE_MOBILE) }))
    }

    /** A call in the system call log. */
    fun call(number: String, date: Long, type: Int, durationSec: Long = 0, name: String? = null, hidden: Boolean = false): Long {
        val v = ContentValues().apply {
            put(CallLog.Calls.NUMBER, number)
            put(CallLog.Calls.DATE, date)
            put(CallLog.Calls.DURATION, durationSec)
            put(CallLog.Calls.TYPE, type)
            put(CallLog.Calls.NUMBER_PRESENTATION, if (hidden) CallLog.Calls.PRESENTATION_RESTRICTED else CallLog.Calls.PRESENTATION_ALLOWED)
            if (name != null) put(CallLog.Calls.CACHED_NAME, name)
        }
        val uri = context.contentResolver.insert(CallLog.Calls.CONTENT_URI, v)!!
        return uri.lastPathSegment!!.toLong()
    }

    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
