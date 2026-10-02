package app.parley.common.calls

import app.parley.common.calls.AutoAnswer.Facts
import app.parley.common.calls.AutoAnswer.Reason
import app.parley.common.calls.DriveProfile.Caller
import app.parley.common.calls.DriveProfile.Connected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveProfileTest {
    private val car = CarDevice("AA:BB:CC:DD:EE:01", "My Golf")
    private val cfg = DriveProfileConfig(cars = listOf(car))

    @Test fun off_until_a_car_is_marked() {
        val d = DriveProfileConfig()
        assertFalse(d.enabled)
        assertTrue(d.announce)
        assertFalse(d.answerFavourites || d.answerChosen || d.silenceUnknown)
        assertNull(DriveProfile.connectedCar(d, listOf(Connected(car.address, car.name))))
    }

    @Test fun car_found_by_address_in_any_spelling() {
        assertEquals(car, DriveProfile.connectedCar(cfg, listOf(Connected("aa-bb-cc-dd-ee-01", null))))
        assertEquals(car, DriveProfile.connectedCar(cfg, listOf(Connected("11:22:33:44:55:66", "Buds"), Connected("aa:bb:cc:dd:ee:01", "x"))))
        // Another device with the car's name but its own address isn't the car.
        assertNull(DriveProfile.connectedCar(cfg, listOf(Connected("11:22:33:44:55:66", "My Golf"))))
        assertNull(DriveProfile.connectedCar(cfg, emptyList()))
    }

    @Test fun car_found_by_name_only_when_the_address_is_hidden() {
        assertEquals(car, DriveProfile.connectedCar(cfg, listOf(Connected("02:00:00:00:00:00", "My Golf"))))
        assertEquals(car, DriveProfile.connectedCar(cfg, listOf(Connected(null, " My Golf "))))
        assertNull(DriveProfile.connectedCar(cfg, listOf(Connected(null, "My golf"))))
        assertNull(DriveProfile.connectedCar(cfg, listOf(Connected(null, null))))
        assertNull(DriveProfile.connectedCar(cfg, listOf(Connected("", ""))))
    }

    @Test fun marking_keeps_one_per_address() {
        val twice = DriveProfile.mark(cfg, car.copy(address = "aa:bb:cc:dd:ee:01", name = "Golf"), true)
        assertEquals(1, twice.cars.size)
        assertEquals("Golf", twice.cars.single().name)
        assertTrue(DriveProfile.isMarked(twice, "AA-BB-CC-DD-EE-01"))
        val off = DriveProfile.mark(twice, car, false)
        assertFalse(off.enabled)
        val two = DriveProfile.mark(cfg, CarDevice("AA:BB:CC:DD:EE:02", "Van"), true)
        assertEquals(2, two.cars.size)
    }

    @Test fun round_trip_and_bad_input() {
        val c = cfg.copy(answerFavourites = true, answerSeconds = 10, silenceUnknown = true, announce = false)
        assertEquals(c, DriveProfileConfig.decode(DriveProfileConfig.encode(c)))
        assertEquals(DriveProfileConfig(), DriveProfileConfig.decode(null))
        assertEquals(DriveProfileConfig(), DriveProfileConfig.decode("{not json"))
        assertEquals(AutoAnswer.DEFAULT_SECONDS, DriveProfileConfig.decode("""{"answerSeconds":7}""").answerSeconds)
        assertEquals(emptyList<CarDevice>(), DriveProfileConfig.decode("""{"cars":[{"address":" ","name":"x"}]}""").cars)
    }

    @Test fun announces_known_visible_callers_only_while_driving() {
        val known = Caller(known = true)
        assertTrue(DriveProfile.announces(cfg, true, known))
        assertFalse(DriveProfile.announces(cfg, false, known))
        assertFalse(DriveProfile.announces(cfg.copy(announce = false), true, known))
        assertFalse(DriveProfile.announces(cfg, true, Caller(known = false)))
        // A private contact hidden by discreet mode: saved, but its name is never said.
        assertFalse(DriveProfile.announces(cfg, true, Caller(known = false, saved = true)))
        assertFalse(DriveProfile.announces(cfg, true, known.copy(hidden = true)))
        assertFalse(DriveProfile.announces(cfg, true, known.copy(blockedOrSpam = true)))
        assertFalse(DriveProfile.announces(cfg, true, known.copy(otherCall = true)))
        assertFalse(DriveProfile.announces(cfg, true, known.copy(emergency = true)))
        assertFalse(DriveProfile.announces(cfg, true, known.copy(quiet = true)))
    }

    @Test fun silences_unknown_callers_only_when_asked() {
        val stranger = Caller(known = false)
        val on = cfg.copy(silenceUnknown = true)
        assertFalse(DriveProfile.silences(cfg, true, stranger))
        assertTrue(DriveProfile.silences(on, true, stranger))
        assertTrue(DriveProfile.silences(on, true, stranger.copy(hidden = true)))
        assertFalse(DriveProfile.silences(on, false, stranger))
        assertFalse(DriveProfile.silences(on, true, Caller(known = true)))
        assertFalse(DriveProfile.silences(on, true, Caller(known = false, saved = true)))
        // Emergency call-backs and calls screening let through on purpose always ring.
        assertFalse(DriveProfile.silences(on, true, stranger.copy(emergency = true)))
        assertFalse(DriveProfile.silences(on, true, stranger.copy(rangThrough = true)))
    }

    @Test fun auto_answer_scope_only_while_driving() {
        assertNull(DriveProfile.answerScope(cfg, true))
        val fav = cfg.copy(answerFavourites = true, answerSeconds = 10)
        assertNull(DriveProfile.answerScope(fav, false))
        assertEquals(AutoAnswer.DriveScope(10, favourites = true, chosen = false), DriveProfile.answerScope(fav, true))
    }

    @Test fun drive_auto_answer_reuses_the_safety_rules() {
        val off = CallExtrasConfig()
        val scope = AutoAnswer.DriveScope(3, favourites = true, chosen = true)
        val fav = Facts(knownCaller = true, favourite = true, drive = scope)
        assertEquals(Reason.DRIVING, AutoAnswer.reason(off, fav))
        assertEquals(3, AutoAnswer.waitSeconds(off, Reason.DRIVING, fav))
        assertEquals(Reason.DRIVING, AutoAnswer.reason(off, Facts(knownCaller = true, chosen = true, drive = scope)))
        // Not a favourite nor chosen, or not driving.
        assertNull(AutoAnswer.reason(off, Facts(knownCaller = true, drive = scope)))
        assertNull(AutoAnswer.reason(off, fav.copy(drive = null)))
        assertNull(AutoAnswer.reason(off, fav.copy(drive = scope.copy(favourites = false))))
        // Never unknown, hidden, blocked, during another call or an emergency.
        assertNull(AutoAnswer.reason(off, fav.copy(knownCaller = false)))
        assertNull(AutoAnswer.reason(off, fav.copy(hidden = true)))
        assertNull(AutoAnswer.reason(off, fav.copy(blockedOrSpam = true)))
        assertNull(AutoAnswer.reason(off, fav.copy(otherCall = true)))
        assertNull(AutoAnswer.reason(off, fav.copy(emergency = true)))
        // The usual situations keep their own wait.
        val headset = CallExtrasConfig(autoAnswerHeadset = true, autoAnswerSeconds = 15)
        val f = Facts(knownCaller = true, headsetConnected = true)
        assertEquals(Reason.HEADSET, AutoAnswer.reason(headset, f))
        assertEquals(15, AutoAnswer.waitSeconds(headset, Reason.HEADSET, f))
        assertNotNull(AutoAnswer.reason(headset, f.copy(favourite = true, drive = scope)))
    }

    @Test fun device_list_merges_paired_connected_and_marked() {
        val van = CarDevice("AA:BB:CC:DD:EE:02", "Van")
        val buds = CarDevice("11:22:33:44:55:66", "Buds")
        val rows = DriveProfile.devices(
            cfg.copy(cars = listOf(car, van)),
            paired = listOf(buds, car),
            connected = listOf(Connected("aa:bb:cc:dd:ee:01", "My Golf"), Connected("77:77:77:77:77:77", "Speaker"), Connected("02:00:00:00:00:00", "Hidden")),
        )
        // Marked first, then by name; the hidden-address device can't be listed.
        assertEquals(listOf("My Golf", "Van", "Buds", "Speaker"), rows.map { it.device.name })
        val golf = rows[0]
        assertTrue(golf.marked && golf.connected && golf.paired)
        val vanRow = rows[1]
        assertTrue(vanRow.marked && !vanRow.connected && !vanRow.paired)
        val speaker = rows[3]
        assertTrue(!speaker.marked && speaker.connected && !speaker.paired)
        assertTrue(DriveProfile.devices(DriveProfileConfig(), emptyList(), emptyList()).isEmpty())
    }
}
