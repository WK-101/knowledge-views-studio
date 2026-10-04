package app.parley.data.circle

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.parley.common.ContactSummary
import app.parley.common.circle.InteractionChannel
import app.parley.common.circle.KeepRhythm
import app.parley.common.circle.LogMode
import app.parley.common.history.CallLogIndex
import app.parley.data.db.AppDatabase
import app.parley.data.db.ContactMetaEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Circle's membership, snoozes, "Log this?" and wishes, over a real (in-memory) database. */
@RunWith(RobolectricTestRunner::class)
class CircleRepositoryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var circle: CircleRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        circle = repo()
    }

    @After fun tearDown() = db.close()

    private fun repo() = CircleRepository(
        app, db.metaDao(), InteractionStore(db.interactionDao()),
        index = { MutableStateFlow<CallLogIndex?>(null) }, contactsFlow = { MutableStateFlow<List<ContactSummary>?>(emptyList()) }, db = db,
    )

    @Test fun aRhythmMakesAMemberAndNoneRemovesIt() = runBlocking {
        assertFalse(circle.isMember("ana"))
        circle.setRhythm("ana", contactId = 7, everyDays = 14)
        assertTrue(circle.isMember("ana"))
        val m = circle.members().single()
        assertEquals("ana", m.lookupKey)
        assertEquals(14, m.days)
        assertEquals(7L, m.meta.contactId)
        circle.setRhythm("ana", contactId = null, everyDays = null)
        assertFalse(circle.isMember("ana"))
        // The row stays (other things live in it); the contact id it had is kept.
        assertEquals(7L, db.metaDao().meta("ana")?.contactId)
        assertFalse(circle.isMember(""))
    }

    @Test fun joiningKeepsWhatElseTheRowHolds() = runBlocking {
        db.metaDao().setMeta(ContactMetaEntity("bo", pinnedNote = "Allergic to nuts", preferredMessenger = "org.thoughtcrime.securesms"))
        circle.setRhythm("bo", contactId = 3, everyDays = 30)
        val row = db.metaDao().meta("bo")!!
        assertEquals("Allergic to nuts", row.pinnedNote)
        assertEquals("org.thoughtcrime.securesms", row.preferredMessenger)
        assertEquals(30, row.reachOutDays)
    }

    @Test fun undoingARemovalPutsTheMembershipBack() = runBlocking {
        circle.setRhythm("cy", contactId = 1, everyDays = 7)
        val before = db.metaDao().meta("cy")!!
        circle.setRhythm("cy", contactId = 1, everyDays = null)
        // A note written meanwhile survives the undo.
        db.metaDao().setMeta(db.metaDao().meta("cy")!!.copy(pinnedNote = "new"))
        circle.restoreMembership(before)
        val after = db.metaDao().meta("cy")!!
        assertEquals(7, after.reachOutDays)
        assertEquals("new", after.pinnedNote)
    }

    @Test fun snoozeWaitsOneGapAndNeedsAMember() = runBlocking {
        val now = 1_700_000_000_000L
        circle.snooze("nobody", now)
        assertNull(db.metaDao().meta("nobody"))
        circle.setRhythm("di", contactId = 2, everyDays = 10)
        circle.snooze("di", now)
        val r = KeepRhythm.decode(db.metaDao().meta("di")!!.rhythm)
        assertTrue(r.isSnoozed(now + 9 * DAY))
        assertFalse(r.isSnoozed(now + 11 * DAY))
    }

    @Test fun onlyCircleMembersAreAskedAndALogIsKeptOnce() = runBlocking {
        circle.onLaunched("stranger", 1, "Stranger", InteractionChannel.SMS)
        assertNull(circle.prompt.value)
        circle.setRhythm("ed", contactId = 4, everyDays = 30)
        circle.onLaunched("ed", 4, "Ed", InteractionChannel.SMS, now = 1_000_000L)
        val p = circle.prompt.value
        assertNotNull(p)
        assertFalse(p!!.autoLogged)
        assertNotNull(circle.accept(p))
        assertNull(circle.prompt.value)
        // A second tap on the same question records nothing more.
        assertNull(circle.accept(p))
        assertEquals(1, circle.interactions.interactionsFor("ed").size)
    }

    @Test fun logModesAreFollowedAndKept() = runBlocking {
        circle.setRhythm("fi", contactId = 5, everyDays = 30)
        circle.updateConfig { it.withLogMode(InteractionChannel.SMS, LogMode.NEVER).withLogMode(InteractionChannel.WHATSAPP, LogMode.ALWAYS) }
        circle.onLaunched("fi", 5, "Fi", InteractionChannel.SMS)
        assertNull(circle.prompt.value)
        circle.onLaunched("fi", 5, "Fi", InteractionChannel.WHATSAPP, now = 2_000_000L)
        assertTrue(circle.prompt.value!!.autoLogged)
        assertEquals(1, circle.interactions.interactionsFor("fi").size)
        // The choice is stored: a new repository (a new process) reads it back.
        assertEquals(LogMode.NEVER, repo().config.value.logMode(InteractionChannel.SMS))
    }

    @Test fun anOccasionIsWishedOnce() = runBlocking {
        val now = System.currentTimeMillis()
        assertFalse(circle.isWished("bday:gus:2026"))
        assertTrue(circle.markWished("gus", 6, "bday:gus:2026", now))
        assertTrue(circle.isWished("bday:gus:2026"))
        assertFalse(circle.markWished("gus", 6, "bday:gus:2026", now))
        assertEquals(1, circle.interactions.interactionsFor("gus").size)
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
