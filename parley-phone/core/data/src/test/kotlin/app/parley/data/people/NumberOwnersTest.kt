package app.parley.data.people

import app.parley.common.calls.LockScreenCaller
import app.parley.common.people.ArchivedCard
import app.parley.common.security.DuressState
import app.parley.common.security.LockPhase
import app.parley.common.security.PrivacyView
import app.parley.data.CallerInfo
import app.parley.data.people.NumberOwners.Owner
import app.parley.data.people.NumberOwners.Use
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Who owns this number" has one answer: every kind of owner, for every use, under every privacy view; the region is
 * the call's SIM's; and one ring finds the owner once.
 */
class NumberOwnersTest {
    private val contactNumber = "+442079460000"
    private val privateNumber = "+442079460001"
    private val archivedNumber = "+442079460002"
    private val strangerNumber = "+442079460003"
    private val failingNumber = "+442079460004"

    private fun info(name: String) = CallerInfo(1L, "key", name, null, null, null, false)

    private val shown = PrivacyView(discreet = false, duress = DuressState(), lockScreen = LockScreenCaller.NAME, appLocked = false)
    private val discreet = shown.copy(discreet = true)
    private val hiding = shown.copy(duress = DuressState(LockPhase.DURESS, hiding = true))
    private val initials = shown.copy(lockScreen = LockScreenCaller.INITIALS)

    private val privateLookups = AtomicInteger()
    private val regions = mutableListOf<String>()
    private var networkOn = true
    private var now = 1_000_000L

    private fun owners() = NumberOwners(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        NumberOwners.Sources(
            contact = { n -> if (n == contactNumber) info("Ada Lovelace") else null },
            private = { n, r ->
                privateLookups.incrementAndGet()
                synchronized(regions) { regions += r }
                // A Keystore operation takes a moment: callers asking at once must still find it once.
                delay(20)
                when (n) {
                    privateNumber -> 7L to info("Dr Rahman")
                    failingNumber -> error("Keystore busy")
                    else -> null
                }
            },
            archived = { n, _ -> if (n == archivedNumber) ArchivedCard(3, "Old Friend", listOf(archivedNumber)) else null },
            network = { n, _ -> if (n == strangerNumber || n == privateNumber || n == failingNumber) "Network Name" else null },
            region = { accountId -> if (accountId == "sim-fr") "FR" else "GB" },
            rememberNetworkNames = { networkOn },
            privacy = { shown },
        ),
        clock = { now },
    )

    @Test fun every_owner_kind_for_every_use() = runBlocking {
        val o = owners()
        for (use in Use.entries) {
            assertEquals(use.name, Owner.Contact(info("Ada Lovelace")), o.owner(contactNumber, null, use, shown))
            assertEquals(use.name, Owner.Private(7L, info("Dr Rahman")), o.owner(privateNumber, null, use, shown))
            assertEquals(use.name, "Old Friend", (o.owner(archivedNumber, null, use, shown) as Owner.Archived).name)
            assertEquals(use.name, Owner.Network("Network Name"), o.owner(strangerNumber, null, use, shown))
            assertEquals(use.name, Owner.Unknown, o.owner("+442079460099", null, use, shown))
            assertTrue(use.name, o.owner(archivedNumber, null, use, shown).saved)
        }
    }

    @Test fun a_hidden_private_contact_reads_as_nobody_with_no_network_name() = runBlocking {
        val o = owners()
        for (view in listOf(discreet, hiding, PrivacyView.CLOSED)) {
            for (use in Use.entries) {
                assertEquals("$view $use", Owner.Unknown, o.owner(privateNumber, null, use, view))
            }
        }
        // Contacts and archived contacts stay named whatever the view.
        assertEquals("Ada Lovelace", o.owner(contactNumber, null, Use.NOTIFICATION, hiding).name)
        assertEquals("Old Friend", o.owner(archivedNumber, null, Use.NOTIFICATION, discreet).name)
    }

    @Test fun the_network_name_needs_the_setting_and_a_number_known_not_private() = runBlocking {
        val o = owners()
        // Under "Initials" the lock screen and notifications don't show a name the network sent; the screens do.
        assertEquals(Owner.Unknown, o.owner(strangerNumber, null, Use.NOTIFICATION, initials))
        assertEquals(Owner.Unknown, o.owner(strangerNumber, null, Use.LOCK_SCREEN, initials))
        assertEquals(Owner.Network("Network Name"), o.owner(strangerNumber, null, Use.SCREEN, initials))
        // A number whose private status couldn't be read never shows one.
        assertEquals(Owner.Unknown, o.owner(failingNumber, null, Use.SCREEN, shown))
        assertTrue(o.find(failingNumber, null).unsure)
        networkOn = false
        assertEquals(Owner.Unknown, o.owner(strangerNumber, null, Use.SCREEN, shown))
    }

    @Test fun one_ring_finds_the_owner_once_for_a_minute() = runBlocking {
        val o = owners()
        val ring = Use.CALL_PATH
        // Screening, the call screen, "never calls you" and the agenda ask at the same moment.
        (1..6).map { async(Dispatchers.Default) { o.find(privateNumber, null, ring) } }.awaitAll()
        assertEquals(1, privateLookups.get())
        now += NumberOwners.MEMO_MS - 1
        o.find(privateNumber, null, Use.NOTIFICATION)
        assertEquals(1, privateLookups.get())
        now += 2
        o.find(privateNumber, null, ring)
        assertEquals(2, privateLookups.get())
        // Who is saved changed: found again.
        o.forget()
        o.find(privateNumber, null, ring)
        assertEquals(3, privateLookups.get())
        // Parley's own screens always look it up again, so a contact saved a moment ago is never missed there.
        o.find(privateNumber, null, Use.SCREEN)
        assertEquals(4, privateLookups.get())
        // ...and the fresh answer serves the ring.
        o.find(privateNumber, null, ring)
        assertEquals(4, privateLookups.get())
    }

    @Test fun a_number_is_read_with_the_region_of_the_calls_sim() = runBlocking {
        val o = owners()
        o.find("0612345678", "sim-fr")
        o.find("0612345678", null)
        assertEquals(listOf("FR", "GB"), regions)
        assertNull(o.find("0612345678", "sim-fr").private)
    }

    @Test fun the_notification_name_follows_the_lock_screen_rule() = runBlocking {
        val o = owners()
        assertEquals("Ada Lovelace", o.notificationName(contactNumber, null, locked = false, privacy = initials))
        assertEquals("AL", o.notificationName(contactNumber, null, locked = true, privacy = initials))
        // A hidden private contact: the number, never the name.
        assertEquals(privateNumber, o.notificationName(privateNumber, null, locked = false, privacy = discreet))
        assertNull(o.notificationName(privateNumber, null, locked = true, privacy = PrivacyView.CLOSED))
    }
}
