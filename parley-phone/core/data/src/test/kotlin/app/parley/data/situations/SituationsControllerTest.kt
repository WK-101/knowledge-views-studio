package app.parley.data.situations

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.BlockAction
import app.parley.common.OffHours
import app.parley.common.OffHoursAllow
import app.parley.common.PolicyClock
import app.parley.common.Schedule
import app.parley.common.backup.RestoreMode
import app.parley.common.backup.Unlock
import app.parley.common.calls.SpeakerDefault
import app.parley.common.situations.DeviceTrigger
import app.parley.common.situations.Situation
import app.parley.common.situations.SituationCause
import app.parley.common.situations.SituationRing
import app.parley.common.situations.SituationSignals
import app.parley.common.situations.Situations
import app.parley.data.DataContainer
import app.parley.data.backup.RestoreOptions
import app.parley.data.people.LabelReferences
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.DayOfWeek
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * Situations against the real stores: switching writes the bundle where each behaviour lives, a new process (or a
 * reboot) finds the snapshot and puts it back, the triggers switch by themselves, and backups carry the list but not
 * the moment.
 */
@RunWith(RobolectricTestRunner::class)
class SituationsControllerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private val mine = OffHours(
        enabled = true, schedule = Schedule(Schedule.WEEKDAYS, 23 * 60, 6 * 60), allow = OffHoursAllow.CONTACTS, action = BlockAction.REJECT,
    )

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.install()
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings().let { s -> s.copy(screening = s.screening.copy(offHours = mine)) } } }
        c.callExtras.update { it.copy(speakerDefault = SpeakerDefault.UNKNOWN_NUMBERS, autoAnswerChosen = false) }
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    /** A second controller on the same files: what a new process (or the phone after a reboot) reads. */
    private fun restarted(signals: SituationSignals? = null) =
        SituationsController(app, c.settings, { c.driveProfile }, { c.callExtras }, { c.roaming }) { signals }

    private fun at(day: DayOfWeek, h: Int) = SituationSignals(PolicyClock(System.currentTimeMillis(), day, h * 60))

    @Test fun onAndOffThroughTheStoresAndAcrossARestart() = runBlocking {
        val sit = c.situations
        assertTrue(sit.edit(Situations.MEETING) { it.copy(speaker = SpeakerDefault.ALWAYS, autoAnswerChosen = true) })
        assertTrue(sit.turnOn(Situations.MEETING))
        val s = c.settings.current()
        assertEquals(OffHoursAllow.FAVOURITES, s.screening.offHours.allow)
        assertEquals(BlockAction.SILENCE, s.screening.offHours.action)
        assertTrue(s.screening.busyReply)
        assertEquals(s.quickReplies.first(), s.screening.busyReplyText)
        assertEquals(SpeakerDefault.ALWAYS, c.callExtras.config.value.speakerDefault)
        assertTrue(c.callExtras.config.value.autoAnswerChosen)

        // The process dies; a new one reads the snapshot and puts it back.
        val again = restarted()
        assertEquals(Situations.MEETING, again.state.value.activeId)
        assertTrue(again.turnOff())
        val back = c.settings.current()
        assertEquals(mine, back.screening.offHours)
        assertFalse(back.screening.busyReply)
        assertEquals(AppSettings.DEFAULT_QUICK_REPLIES, back.quickReplies)
        assertEquals(SpeakerDefault.UNKNOWN_NUMBERS, c.callExtras.config.value.speakerDefault)
        assertFalse(c.callExtras.config.value.autoAnswerChosen)
        assertNull(restarted().state.value.activeId)
    }

    @Test fun aWindowThatEndedWhileThePhoneWasOffIsUndoneAtTheNextLook() = runBlocking {
        assertTrue(c.situations.edit(Situations.NIGHT) { it.copy(schedule = Situations.NIGHT_WINDOW) })
        val evening = restarted(at(DayOfWeek.MONDAY, 23))
        assertTrue(evening.reconcile())
        assertEquals(SituationCause.SCHEDULE, evening.state.value.cause)
        assertEquals(OffHoursAllow.FAVOURITES, c.settings.current().screening.offHours.allow)
        // Rebooted in the morning: the first look (app start, the tile or a call) puts back what was set.
        val morning = restarted(at(DayOfWeek.TUESDAY, 8))
        assertEquals(Situations.NIGHT, morning.state.value.activeId)
        assertTrue(morning.reconcile())
        assertNull(morning.state.value.activeId)
        assertEquals(mine, c.settings.current().screening.offHours)
        // Nothing to do: nothing written.
        assertFalse(morning.reconcile())
    }

    @Test fun theCarSwitchesDrivingOnAndItsSimIsOffered() = runBlocking {
        assertTrue(c.situations.edit(Situations.DRIVING) { it.copy(device = DeviceTrigger.CAR, simId = "sim-2", simLabel = "Work") })
        val inCar = restarted(at(DayOfWeek.MONDAY, 9).copy(car = true))
        assertTrue(inCar.reconcile())
        assertEquals(Situations.DRIVING, inCar.state.value.activeId)
        assertEquals("sim-2", inCar.activeSim())
        assertTrue(c.driveProfile.config.value.answerFavourites)
        assertTrue(restarted(at(DayOfWeek.MONDAY, 10)).reconcile())
        assertFalse(c.driveProfile.config.value.answerFavourites)
    }

    @Test fun deletingTheOneOnPutsBackFirst() = runBlocking {
        val id = c.situations.newId()
        assertTrue(c.situations.save(Situation(id, name = "Gym", ring = SituationRing.EVERYONE)))
        assertTrue(c.situations.turnOn(id))
        assertFalse(c.settings.current().screening.offHours.enabled)
        assertTrue(c.situations.delete(id))
        assertEquals(mine, c.settings.current().screening.offHours)
        assertTrue(c.situations.list.value.none { it.id == id })
    }

    @Test fun backupsCarryTheSituationsButNotTheMoment() = runBlocking {
        val passphrase = "correct horse battery staple"
        val file = File(app.cacheDir, "situations.parleybackup")
        val sit = c.situations
        val gym = Situation(sit.newId(), name = "Gym", ring = SituationRing.FAVOURITES, schedule = Schedule(Schedule.WEEKDAYS, 18 * 60, 19 * 60))
        assertTrue(sit.save(gym))
        assertTrue(sit.edit(Situations.MEETING) { it.copy(speaker = SpeakerDefault.ALWAYS) })
        assertTrue(sit.turnOn(Situations.MEETING))

        c.backup.setupKeys(passphrase.toCharArray())
        val out = c.backup.backupNow(scheduled = false, target = Uri.fromFile(file))
        assertTrue(out.message, out.ok)
        assertEquals(emptyList<String>(), out.failedSections)

        // The new phone: its own Night, none of the backup's own.
        assertTrue(sit.turnOff())
        assertTrue(sit.delete(gym.id))
        assertTrue(sit.delete(Situations.MEETING))
        assertTrue(sit.edit(Situations.NIGHT) { it.copy(ring = SituationRing.CONTACTS) })

        val opened = c.backup.open(Uri.fromFile(file), Unlock.Passphrase(passphrase.toCharArray()))
        val report = c.backup.restore(opened, c.backup.plan(opened, RestoreMode.MERGE), RestoreOptions(settings = true))
        assertEquals(report.skipped.toString(), emptyList<String>(), report.skipped)
        val list = sit.list.value
        assertEquals(gym, list.first { it.id == gym.id })
        assertEquals(SpeakerDefault.ALWAYS, list.first { it.id == Situations.MEETING }.speaker)
        assertEquals(SituationRing.CONTACTS, list.first { it.id == Situations.NIGHT }.ring)
        // Which one was on stays with the old phone.
        assertNull(sit.state.value.activeId)
    }

    /** A label "Family" on this phone; its group row. */
    private fun family(): Long {
        val v = ContentValues().apply {
            put(ContactsContract.Groups.TITLE, "Family")
            put(ContactsContract.Groups.ACCOUNT_TYPE, "com.example")
            put(ContactsContract.Groups.ACCOUNT_NAME, "me")
        }
        return ContentUris.parseId(app.contentResolver.insert(ContactsContract.Groups.CONTENT_URI, v)!!)
    }

    private fun rename(row: Long, title: String) {
        val values = ContentValues().apply { put(ContactsContract.Groups.TITLE, title) }
        app.contentResolver.update(ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, row), values, null, null)
    }

    /** Off hours 22–07 letting only Family ring. */
    private val familyHours = OffHours(enabled = true, schedule = Situations.NIGHT_WINDOW, allow = OffHoursAllow.LABEL, labelTitle = "Family")

    @Test fun aLabelRenamedOrDeletedWhileOnLeavesNoAllDaySilencing() = runBlocking {
        val row = family()
        c.settings.update { s -> s.copy(screening = s.screening.copy(offHours = familyHours)) }
        val refs = LabelReferences(c, c.peoplePrefs)
        // Renamed while Meeting (Favourites) is on: off hours follow the label, and Meeting puts back the renamed label.
        assertTrue(c.situations.turnOn(Situations.MEETING))
        // The labels screen renames the group, then has everything that names it follow.
        rename(row, "Home")
        refs.renamed(mapOf("Family" to "Home"))
        assertTrue(c.situations.turnOff())
        assertEquals(familyHours.copy(labelTitle = "Home"), c.settings.current().screening.offHours)

        // A Situation letting the label ring, on while the label is deleted.
        val id = c.situations.newId()
        assertTrue(c.situations.save(Situation(id, name = "Visit", ring = SituationRing.LABEL, ringLabel = "Home", ringLabelId = row)))
        assertTrue(c.situations.turnOn(id))
        assertEquals("Home", c.settings.current().screening.offHours.labelTitle)
        app.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, row), null, null)
        val said = refs.deleted(setOf("Home"))
        // Your own off hours let only that label ring: they're switched off, and you're told.
        assertTrue(said, said!!.contains("Home"))
        // The one on now lets Favourites ring at once, and its line says the label is gone.
        val live = c.settings.current().screening.offHours
        assertTrue(live.enabled)
        assertEquals(OffHoursAllow.FAVOURITES, live.allow)
        assertTrue(c.situations.list.value.first { it.id == id }.ringLabelGone)
        assertTrue(c.situations.turnOff())
        // Nothing silenced all day once it's off.
        assertFalse(c.settings.current().screening.offHours.enabled)
    }

    @Test fun aLabelRenamedInAnotherAppIsFollowedAndAGoneOneFallsBackToFavourites() = runBlocking {
        val row = family()
        val sit = SituationsController(app, c.settings, { c.driveProfile }, { c.callExtras }, { c.roaming }, c.scope, labels = {
            val columns = arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE)
            app.contentResolver.query(ContactsContract.Groups.CONTENT_URI, columns, null, null, null)
                ?.use { q -> buildMap { while (q.moveToNext()) put(q.getLong(0), q.getString(1).orEmpty()) } }
        })
        val id = sit.newId()
        assertTrue(sit.save(Situation(id, name = "Visit", ring = SituationRing.LABEL, ringLabel = "Family", ringLabelId = row)))
        rename(row, "Kin")
        assertTrue(sit.turnOn(id))
        assertEquals("Kin", c.settings.current().screening.offHours.labelTitle)
        assertEquals("Kin", sit.list.value.first { it.id == id }.ringLabel)
        assertTrue(sit.turnOff())
        app.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, row), null, null)
        assertTrue(sit.turnOn(id))
        assertEquals(OffHoursAllow.FAVOURITES, c.settings.current().screening.offHours.allow)
        assertTrue(sit.list.value.first { it.id == id }.ringLabelGone)
        assertTrue(sit.turnOff())
        assertEquals(mine, c.settings.current().screening.offHours)
    }

    @Test fun theLookBeforeScreeningNeverWaitsForTheSwitch() = runBlocking {
        assertTrue(c.situations.edit(Situations.NIGHT) { it.copy(schedule = Situations.NIGHT_WINDOW) })
        // The first look (from memory) is quick; the switch's own reads are slow, as a busy phone's disk can be.
        val calls = AtomicInteger()
        val slow = SituationsController(app, c.settings, { c.driveProfile }, { c.callExtras }, { c.roaming }, c.scope) {
            if (calls.incrementAndGet() > 1) Thread.sleep(1_500)
            at(DayOfWeek.MONDAY, 23)
        }
        val took = measureTimeMillis { slow.lookBriefly(300) }
        assertTrue("waited $took ms", took < 1_200)
        // The switch still happens, in the background.
        withTimeout(10_000) { slow.state.first { it.activeId == Situations.NIGHT && !it.pending } }
        assertEquals(OffHoursAllow.FAVOURITES, c.settings.current().screening.offHours.allow)
        // Nothing to switch: back at once.
        assertTrue(measureTimeMillis { restarted(at(DayOfWeek.MONDAY, 23)).lookBriefly(300) } < 300)
    }

    @Test fun movingAwayFromAWindowsSituationKeepsItOffUntilTheWindowEnds() = runBlocking {
        assertTrue(c.situations.edit(Situations.NIGHT) { it.copy(schedule = Situations.NIGHT_WINDOW) })
        val night = restarted(at(DayOfWeek.MONDAY, 23))
        assertTrue(night.reconcile())
        // The tile: the next one, then Off.
        assertTrue(night.turnOn(Situations.TRAVELLING))
        assertTrue(night.turnOff())
        night.reconcile()
        assertNull(night.state.value.activeId)
        assertEquals(mine, c.settings.current().screening.offHours)
    }

    @Test fun editingTheOneOnByItselfLooksAtItsWindowAgain() = runBlocking {
        assertTrue(c.situations.edit(Situations.NIGHT) { it.copy(schedule = Situations.NIGHT_WINDOW) })
        val sit = restarted(at(DayOfWeek.MONDAY, 23))
        assertTrue(sit.reconcile())
        assertEquals(Situations.NIGHT, sit.state.value.activeId)
        // Its window moved to the morning: it no longer holds, so Night goes off and what was set comes back.
        assertTrue(sit.edit(Situations.NIGHT) { it.copy(schedule = Schedule(Schedule.ALL_DAYS, 9 * 60, 10 * 60)) })
        assertNull(sit.state.value.activeId)
        assertEquals(mine, c.settings.current().screening.offHours)
    }

    @Test fun aSwitchCutShortByAProcessDeathIsFinishedAtTheNextLook() = runBlocking {
        assertTrue(c.situations.turnOn(Situations.MEETING))
        val applied = c.settings.current().screening.offHours
        // The process died after the state was kept and before the stores were written.
        app.getSharedPreferences("parley_situation_state", Context.MODE_PRIVATE).edit()
            .putString("state", c.situations.state.value.copy(pending = true).encode()).commit()
        c.settings.update { s -> s.copy(screening = s.screening.copy(offHours = mine)) }
        val again = restarted()
        assertTrue(again.reconcile())
        assertEquals(applied, c.settings.current().screening.offHours)
        assertFalse(again.state.value.pending)
        assertTrue(again.turnOff())
        assertEquals(mine, c.settings.current().screening.offHours)
    }

    @Test fun aBackupMadeWhileOneIsOnRestoresWhatWasSetBeforeIt() = runBlocking {
        val passphrase = "correct horse battery staple"
        val file = File(app.cacheDir, "situation-on.parleybackup")
        assertTrue(c.situations.edit(Situations.MEETING) { it.copy(speaker = SpeakerDefault.ALWAYS) })
        assertTrue(c.situations.turnOn(Situations.MEETING))
        c.backup.setupKeys(passphrase.toCharArray())
        assertTrue(c.backup.backupNow(scheduled = false, target = Uri.fromFile(file)).ok)
        assertTrue(c.situations.turnOff())

        val opened = c.backup.open(Uri.fromFile(file), Unlock.Passphrase(passphrase.toCharArray()))
        val report = c.backup.restore(opened, c.backup.plan(opened, RestoreMode.MERGE), RestoreOptions(settings = true))
        assertEquals(report.skipped.toString(), emptyList<String>(), report.skipped)
        // Not Meeting's all-day off hours, speaker and replies: what was set before it.
        assertNull(c.situations.state.value.activeId)
        val s = c.settings.current()
        assertEquals(mine, s.screening.offHours)
        assertFalse(s.screening.busyReply)
        assertEquals(AppSettings.DEFAULT_QUICK_REPLIES, s.quickReplies)
        assertEquals(SpeakerDefault.UNKNOWN_NUMBERS, c.callExtras.config.value.speakerDefault)
    }

    @Test fun theControllerIsBuiltOnlyWhenAskedFor() {
        assertNull(c.situationsIfReady())
        val sit = c.situations
        assertEquals(sit, c.situationsIfReady())
    }
}
