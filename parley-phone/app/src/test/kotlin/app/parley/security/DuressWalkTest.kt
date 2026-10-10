package app.parley.security

import android.Manifest
import android.app.Application
import android.app.Notification
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.AppTelecomDependencies
import app.parley.blocking.BlockingNotifier
import app.parley.calls.NoticeCaller
import app.parley.common.AppSettings
import app.parley.common.BlockAction
import app.parley.common.BlockReason
import app.parley.common.Decision
import app.parley.common.ScreeningResult
import app.parley.common.ScreeningSettings
import app.parley.common.Verdict
import app.parley.common.VerdictKind
import app.parley.common.backup.Unlock
import app.parley.common.catching
import app.parley.common.cases.CaseFiles
import app.parley.common.cases.CaseMode
import app.parley.common.calls.SafeWord
import app.parley.common.security.DuressMachine
import app.parley.common.security.PinVerdict
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.PhoneEnv
import app.parley.data.ScreenRequest
import app.parley.data.ScreenedCall
import app.parley.data.people.CallerCards
import app.parley.data.people.NumberOwners
import app.parley.data.security.Concealment
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.vault.VaultCrypto
import app.parley.rescue.RescueCalls
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * One walk through what a duress unlock promises (docs/SECURITY_MODEL.md, "Duress unlock" and "One privacy rule"):
 * with the hiding on, no notification names a private contact, Rescue call shows none of its last choices, and no part
 * of a backup carries a private contact's name, a case file, a safe word or the Rescue call's choices. Every check reads
 * through the one privacy view; a feature that read the switches its own way would show here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DuressWalkTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private val private = "+1 202 555 0100"
    private val name = "Rahman"
    private val bank = "+1 202 555 0142"
    private val secrets = listOf(name, "CLM-77815", "Blue heron", "Night shift")

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
        // A case file with a reference number, a family safe word, and Rescue call's last choices naming them.
        c.cases.update { CaseFiles.setMode(it, "Bank", listOf(bank), false, CaseMode.ON, 1, "US", "bank") }
        assertTrue(c.cases.addReference("bank", "Claim", "CLM-77815", typed = false))
        assertTrue(c.familySafety.setSafeWord("Family", SafeWord("Where did we meet?", "Blue heron")))
        RescueCalls.saveChoices(context, RescueCalls.Choices(name = name, number = private, clipName = "Night shift"))
        Unit
    }

    @After fun tearDown() {
        Concealment.move(DuressMachine.pinEntered(Concealment.state.value, PinVerdict.NORMAL, false))
        c.scope.cancel()
    }

    private fun duress() = Concealment.move(DuressMachine.pinEntered(Concealment.state.value, PinVerdict.DURESS, false))

    private fun silenced() = ScreenedCall(
        request = ScreenRequest(private, hidden = false),
        result = ScreeningResult(Decision.Block(BlockAction.SILENCE, BlockReason.OFF_HOURS), emptyList(), Verdict(VerdictKind.BLOCKED, "Quiet hours")),
        isContact = true, contactName = name, logId = null, settings = ScreeningSettings(busyReply = true),
    )

    private fun Notification.said(): String = listOfNotNull(
        extras.getCharSequence(Notification.EXTRA_TITLE), extras.getCharSequence(Notification.EXTRA_TEXT),
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT), publicVersion?.extras?.getCharSequence(Notification.EXTRA_TITLE),
    ).joinToString(" ")

    /** Everything a notification about a call from the private contact says, through every notice path. */
    private suspend fun notices(): String {
        val region = PhoneEnv.countryIso(context)
        val privacy = c.privacy.now()
        return listOfNotNull(
            BlockingNotifier.build(context, c, silenced(), locked = false)?.builder?.build()?.said(),
            BlockingNotifier.build(context, c, silenced(), locked = true)?.builder?.build()?.said(),
            // The missed-call notice's name and its caller-card line.
            NoticeCaller.find(c, private, region, privacy).name,
            CallerCards.missedCallLine(c, private, privacy.privateHidden, region),
            // The To call reminder and every other notification that names a number.
            c.numberOwners.notificationName(private, null, locked = false, privacy = privacy),
            c.numberOwners.owner(private, null, NumberOwners.Use.NOTIFICATION, privacy).name,
        ).joinToString(" | ")
    }

    /**
     * What the call screen shows for a call from the private contact (the ringing call asks through the same memo as
     * screening, so a name found before the hiding must not come back from it), and whether the call path still knows
     * the caller is saved (so nothing treats them as a stranger).
     */
    private suspend fun callScreen(): Pair<String?, Boolean> {
        val deps = AppTelecomDependencies(context, c)
        return deps.callerInfo(private, null)?.name to deps.isSavedCaller(private, null)
    }

    /** Every feature part of a backup, as the backup would write it now (a part that can't be read is left out). */
    private suspend fun backupParts(): String = buildList {
        for (part in c.backup.extras()) add(catching { part.export().values.joinToString(" ") }.getOrDefault(""))
    }.joinToString(" | ")

    private val file by lazy { File(context.cacheDir, "duress.parleybackup") }
    private val passphrase = "correct horse battery staple"

    /** A whole backup written now and read back: every section's text, and the private contacts' part. */
    private suspend fun wholeBackup(): Pair<String, Int> {
        file.delete()
        val out = c.backup.backupNow(scheduled = false, target = Uri.fromFile(file))
        assertTrue(out.message, out.ok)
        val reader = c.backup.open(Uri.fromFile(file), Unlock.Passphrase(passphrase.toCharArray())).reader
        val text = listOf(
            runCatching { reader.settings().toString() }.getOrDefault(""),
            runCatching { reader.contacts { it.toList().toString() } }.getOrDefault(""),
            runCatching { reader.callLog { it.toList().toString() } }.getOrDefault(""),
            runCatching { reader.callHistory { it.toList().toString() }.orEmpty() }.getOrDefault(""),
            runCatching { reader.blocking().toString() }.getOrDefault(""),
        ).joinToString(" | ")
        return text to reader.vault().size
    }

    @Test fun a_duress_unlock_hides_every_private_trace_in_notifications_rescue_and_backups() = runBlocking {
        // Before: the private contact is named, and what the hiding will cover is really there.
        assertTrue(notices().contains(name))
        assertEquals(name to true, callScreen())
        assertEquals(name, RescueCalls.choices(context).name)
        assertTrue(backupParts().contains("CLM-77815"))
        c.backup.setupKeys(passphrase.toCharArray())
        val (before, privateBefore) = wholeBackup()
        assertTrue("the case file is in a backup", before.contains("CLM-77815"))
        assertTrue("so are the private contacts", privateBefore > 0)

        duress()
        assertTrue(c.privacy.now().privateHidden)

        val said = notices()
        assertFalse(said, said.contains(name))
        // The call screen names nobody, yet the call path still knows the caller is saved (they ring as before).
        assertEquals(null to true, callScreen())
        val rescue = RescueCalls.choices(context)
        assertEquals(RescueCalls.Choices(), rescue)
        val parts = backupParts()
        for (secret in secrets) assertFalse("$secret in a backup part", parts.contains(secret))
        // The whole backup, every section: no private contacts, no case file, no safe word, no Rescue choices.
        val (whole, privateParts) = wholeBackup()
        for (secret in secrets) assertFalse("$secret in the backup", whole.contains(secret))
        assertEquals(0, privateParts)

        // Locking Parley ends the session, not the hiding: still nothing.
        Concealment.lock()
        assertFalse(notices().contains(name))
        assertEquals(RescueCalls.Choices(), RescueCalls.choices(context))

        // The real PIN brings everything back, nothing lost.
        Concealment.move(DuressMachine.pinEntered(Concealment.state.value, PinVerdict.NORMAL, false))
        assertTrue(notices().contains(name))
        assertEquals(name to true, callScreen())
        assertEquals(name, RescueCalls.choices(context).name)
        assertTrue(backupParts().contains("CLM-77815"))
    }
}
