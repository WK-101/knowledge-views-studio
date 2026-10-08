package app.parley.blocking

import android.Manifest
import android.app.Application
import android.app.Notification
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.BlockAction
import app.parley.common.BlockReason
import app.parley.common.Decision
import app.parley.common.ScreeningResult
import app.parley.common.ScreeningSettings
import app.parley.common.Verdict
import app.parley.common.VerdictKind
import app.parley.common.calls.LockScreenCaller
import app.parley.common.security.DuressMachine
import app.parley.common.security.PinVerdict
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.PhoneEnv
import app.parley.data.ScreenRequest
import app.parley.data.ScreenedCall
import app.parley.data.security.Concealment
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Blocked, silenced and quiet-hours notices name a private contact only where the missed-call notice would: never in
 * discreet mode or during a duress hiding, shortened on a locked phone as "Caller on the lock screen" says, and never
 * when it can't be told whether the caller is private. The lock screen's own version names nobody and shows no number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BlockingNoticePrivacyTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private val private = "+1 202 555 0100"
    private val stranger = "+1 202 555 0177"
    private val name = "Rahman"

    @Before fun setUp() = runBlocking {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        VaultCrypto.appContext = context
        Concealment.init(context)
        Concealment.forgetForTest(newProcess = true)
        c = DataContainer(context)
        c.settings.update { AppSettings() }
        c.vault.save(null, ContactDetails(given = name, phones = listOf(DataItem(null, private, Phone.TYPE_MOBILE))))
        Unit
    }

    @After fun tearDown() {
        FakeAndroidKeyStore.failure = null
        Concealment.move(DuressMachine.pinEntered(Concealment.state.value, PinVerdict.NORMAL, false))
        c.scope.cancel()
    }

    private fun silenced(number: String, reason: BlockReason = BlockReason.RULE, settings: ScreeningSettings = ScreeningSettings()) = ScreenedCall(
        request = ScreenRequest(number, hidden = false),
        result = ScreeningResult(Decision.Block(BlockAction.SILENCE, reason), emptyList(), Verdict(VerdictKind.BLOCKED, "Blocked by rule: Doctors")),
        // What screening hands over: it knew the private contact by name.
        isContact = number == private,
        contactName = if (number == private) name else null,
        logId = null,
        settings = settings,
    )

    private fun notice(e: ScreenedCall, locked: Boolean = false): Pair<Int, Notification> = runBlocking {
        val built = BlockingNotifier.build(context, c, e, locked)!!
        built.id to built.builder.build()
    }

    private val Notification.title: String get() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private val Notification.text: String get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

    /** Everything a notification says, for "never names them" checks. */
    private fun Notification.said(): String = listOf(title, text, extras.getCharSequence(Notification.EXTRA_BIG_TEXT)).joinToString(" ")

    private fun digits(n: String) = n.filter(Char::isDigit).takeLast(7)

    private fun assertPublicNamesNobody(n: Notification, number: String) {
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, n.visibility)
        val public = n.publicVersion
        assertNotNull("a lock-screen version", public)
        val said = public!!.said()
        assertFalse(said, said.contains(name))
        assertFalse(said, said.filter(Char::isDigit).contains(digits(number)))
    }

    @Test fun a_private_contact_is_named_while_private_contacts_show() {
        val (_, n) = notice(silenced(private))
        assertTrue(n.title, n.title.contains(name))
        assertPublicNamesNobody(n, private)
        assertEquals("Silenced call", n.publicVersion!!.title)
    }

    @Test fun discreet_mode_shows_them_as_a_number() = runBlocking {
        c.settings.update { it.copy(hideVault = true) }
        val (_, n) = notice(silenced(private))
        assertFalse(n.said(), n.said().contains(name))
        assertTrue(n.title, n.title.filter(Char::isDigit).contains(digits(private)))
        // The rule that caught them can name their label: it stays out, as it does for nobody else's.
        assertFalse(n.said(), n.said().contains("Doctors"))
        assertPublicNamesNobody(n, private)
    }

    @Test fun a_duress_unlock_shows_them_as_a_number_and_offers_no_quiet_hours_reply() {
        val quiet = ScreeningSettings(busyReply = true)
        // Before: someone you know, silenced by off hours, gets the one-tap reply.
        val (beforeId, before) = notice(silenced(private, BlockReason.OFF_HOURS, quiet))
        assertTrue(before.title, before.title.contains(name))
        assertTrue(beforeId != BlockingNotifier.ID_BLOCKED)
        assertPublicNamesNobody(before, private)

        Concealment.move(DuressMachine.pinEntered(Concealment.state.value, PinVerdict.DURESS, false))
        val (id, n) = notice(silenced(private, BlockReason.OFF_HOURS, quiet))
        assertFalse(n.said(), n.said().contains(name))
        // The same notice a stranger silenced by off hours gets.
        assertEquals(BlockingNotifier.ID_BLOCKED, id)
        assertTrue(n.actions.orEmpty().none { it.title.toString() == context.getString(app.parley.R.string.blk_n_reply) })
        assertPublicNamesNobody(n, private)
    }

    @Test fun the_lock_screen_choice_shortens_the_name_while_locked() = runBlocking {
        c.settings.update { it.copy(lockScreenCaller = LockScreenCaller.INITIALS) }
        assertEquals("Silenced call from R", notice(silenced(private), locked = true).second.title)
        assertTrue(notice(silenced(private), locked = false).second.title.contains(name))
        c.settings.update { it.copy(lockScreenCaller = LockScreenCaller.NONE) }
        val (_, n) = notice(silenced(private), locked = true)
        assertEquals("Silenced call", n.title)
        // A stranger's number goes too with "Nothing".
        assertEquals("Silenced call", notice(silenced(stranger), locked = true).second.title)
        assertPublicNamesNobody(n, private)
    }

    @Test fun a_caller_whose_privacy_cant_be_read_is_treated_as_private() {
        FakeAndroidKeyStore.failure = { java.security.KeyStoreException("busy") }
        val (_, n) = notice(silenced(private))
        assertFalse(n.said(), n.said().contains(name))
        assertFalse(n.said(), n.said().contains("Doctors"))
        assertPublicNamesNobody(n, private)
    }

    @Test fun a_network_name_shows_only_with_the_setting_and_never_for_a_private_contact() = runBlocking {
        val region = PhoneEnv.countryIso(context)
        val now = System.currentTimeMillis()
        c.networkNames.record(stranger, "Ravi Kumar", now, null, region)
        c.networkNames.record(private, "Clinic Front Desk", now, null, region)
        assertFalse(notice(silenced(stranger)).second.title.contains("Ravi Kumar"))
        c.settings.update { it.copy(rememberNetworkNames = true) }
        assertTrue(notice(silenced(stranger)).second.title.contains("Ravi Kumar"))
        c.settings.update { it.copy(rememberNetworkNames = true, hideVault = true) }
        val (_, n) = notice(silenced(private))
        assertFalse(n.said(), n.said().contains("Clinic") || n.said().contains(name))
        assertPublicNamesNobody(n, private)
    }
}
