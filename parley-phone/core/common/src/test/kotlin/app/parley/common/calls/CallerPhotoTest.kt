package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerPhotoTest {
    @Test fun the_contact_overrides_the_setting_either_way() {
        assertTrue(CallerPhoto.shows(global = true, override = null))
        assertFalse(CallerPhoto.shows(global = false, override = null))
        assertTrue(CallerPhoto.shows(global = false, override = true))
        assertFalse(CallerPhoto.shows(global = true, override = false))
    }

    @Test fun round_trip_and_bad_lines() {
        val m = mapOf("0r1-ABC" to false, "parley-private:7" to true)
        assertEquals(m, CallerPhoto.decode(CallerPhoto.encode(m)))
        assertEquals(mapOf("k" to true), CallerPhoto.decode("k\t1\nbroken\n\tx\nz\t2"))
        assertEquals(emptyMap<String, Boolean>(), CallerPhoto.decode(null))
    }
}
