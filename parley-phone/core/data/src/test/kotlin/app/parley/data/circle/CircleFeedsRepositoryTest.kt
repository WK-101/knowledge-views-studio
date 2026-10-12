package app.parley.data.circle

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.parley.common.ContactSummary
import app.parley.common.circle.KeepRhythm
import app.parley.common.circle.LabelFeed
import app.parley.common.circle.NaturalRhythm
import app.parley.common.history.CallLogIndex
import app.parley.common.people.ContactRef
import app.parley.data.backup.BackupExtras
import app.parley.data.db.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Label-fed Circle members, seasons and "They're fine", over a real (in-memory) database. */
@RunWith(RobolectricTestRunner::class)
class CircleFeedsRepositoryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val day = NaturalRhythm.DAY
    private lateinit var db: AppDatabase
    private lateinit var circle: CircleRepository
    private var feeds: Map<String, List<LabelFeed>> = emptyMap()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        circle = CircleRepository(
            app, db.metaDao(), InteractionStore(db.interactionDao()),
            index = { MutableStateFlow<CallLogIndex?>(null) }, contactsFlow = { MutableStateFlow<List<ContactSummary>?>(emptyList()) }, db = db,
            labelFeeds = { feeds },
        )
    }

    @After fun tearDown() = db.close()

    @Test fun membersAreOwnAndLabelFedEachOnceAndOwnRhythmWins() = runBlocking {
        val family = LabelFeed("Family", 14)
        val friends = LabelFeed("Friends", 7)
        val privateKey = ContactRef.privateKey(5)
        feeds = mapOf("ana" to listOf(family, friends), "bo" to listOf(family), privateKey to listOf(family))
        circle.setRhythm("bo", contactId = 2, everyDays = 30)
        val members = circle.members().associateBy { it.lookupKey }
        assertEquals(setOf("ana", "bo", privateKey), members.keys)
        assertEquals(7, members.getValue("ana").days)
        assertEquals("Friends", members.getValue("ana").rhythmAt(System.currentTimeMillis()).via)
        assertFalse(members.getValue("ana").own)
        // Bo's own rhythm wins over the label's.
        assertEquals(30, members.getValue("bo").days)
        assertNull(members.getValue("bo").rhythmAt(System.currentTimeMillis()).via)
        assertTrue(circle.isMember("ana"))
        assertEquals(listOf(family, friends), circle.feedsFor("ana"))

        // Switching the label off takes out only who was in through it.
        feeds = emptyMap()
        assertEquals(listOf("bo"), circle.members().map { it.lookupKey })
        assertFalse(circle.isMember("ana"))
    }

    @Test fun aLabelFedMemberCanBeSnoozedAndRemindedWithoutJoiningForGood() = runBlocking {
        feeds = mapOf("ana" to listOf(LabelFeed("Family", 14)))
        val now = 1_000 * day
        circle.snooze("ana", now)
        circle.nudged("ana", now)
        val row = db.metaDao().meta("ana")!!
        assertNull(row.reachOutDays)
        assertEquals(now + 14 * day, KeepRhythm.decode(row.rhythm).snoozedUntil)
        assertEquals(now, row.lastNudgedAt)
        // The row alone doesn't make a member: without the label they're out.
        feeds = emptyMap()
        assertTrue(circle.members().isEmpty())
    }

    @Test fun aSeasonIsSetEndsAndIsClearedOnce() = runBlocking {
        circle.setRhythm("gran", contactId = 1, everyDays = 14)
        val now = System.currentTimeMillis()
        circle.setSeason("gran", 3, now + 10 * day)
        assertEquals(3, circle.members().single().days)
        // Changing the usual rhythm keeps the season running.
        circle.setRhythm("gran", contactId = 1, everyDays = 30)
        assertEquals(3, circle.members().single().days)
        assertTrue(circle.endedSeasons(now).isEmpty())
        val later = now + 10 * day
        assertEquals(listOf("gran"), circle.endedSeasons(later).map { it.lookupKey })
        circle.clearEndedSeasons(listOf("gran"), later)
        assertTrue(circle.endedSeasons(later).isEmpty())
        assertEquals(30, circle.members().single().days)
        // No season for someone outside the Circle.
        circle.setSeason("stranger", 3, now + day)
        assertNull(db.metaDao().meta("stranger"))
    }

    @Test fun theyReFineLastsAYearAndKeepsOutOfTheQuieterPick() = runBlocking {
        val now = 1_000 * day
        circle.markFine("marco", contactId = 9, now = now)
        assertEquals(setOf("marco"), circle.notDrifting(now))
        assertFalse(circle.isMember("marco"))
        // Joining the Circle keeps the mark.
        circle.setRhythm("marco", 9, 30)
        assertEquals(now + 365 * day, KeepRhythm.decode(db.metaDao().meta("marco")!!.rhythm).fineUntil)
    }

    @Test fun theBackupHoldsOnlyOwnRhythms() = runBlocking {
        feeds = mapOf("ana" to listOf(LabelFeed("Family", 14)))
        circle.setRhythm("bo", contactId = 2, everyDays = 30)
        val members = JSONArray(circle.backupExtras.export().getValue("${BackupExtras.PREFIX}circle.members"))
        assertEquals(1, members.length())
        assertEquals("bo", members.getJSONObject(0).getString("k"))
    }
}
