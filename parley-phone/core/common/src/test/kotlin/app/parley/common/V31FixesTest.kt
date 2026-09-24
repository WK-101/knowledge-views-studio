package app.parley.common

import app.parley.common.history.CallExport
import app.parley.common.people.TemporaryExpiry
import app.parley.common.record.Col
import app.parley.common.record.Mime
import app.parley.common.vcard.VCardStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** Stored, exported and exchanged values never follow the app language (Arabic writes non-ASCII digits). */
class LocaleFormattingTest {
    private lateinit var saved: Locale

    @Before fun arabic() {
        saved = Locale.getDefault()
        // Arabic with Arabic-Indic digits, as Android formats it (the JVM's plain "ar" may use Latin digits).
        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
        // The default locale must really localise digits, or these tests prove nothing.
        assertFalse("%d".format(12).all { it in '0'..'9' })
    }

    @After fun restore() = Locale.setDefault(saved)

    private fun ascii(s: String) = assertTrue("non-ASCII digits in \"$s\"", s.all { it.code < 128 })

    @Test fun event_dates_are_ascii() {
        assertEquals("1990-03-12", EventDate(1990, 3, 12).format())
        assertEquals("--03-12", EventDate(null, 3, 12).format())
    }

    @Test fun export_durations_are_ascii() {
        assertEquals("1:02:03", CallExport.hms(3723))
    }

    @Test fun vcard_import_dates_are_ascii() {
        val list = VCardStream.readAll("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Old\r\nBDAY:1970-01-02\r\nEND:VCARD\r\n").first
        val bday = list.single().raws.flatMap { it.rows }.single { it.mimeType == Mime.EVENT }[Col.D1]
        assertEquals("1970-01-02", bday)
    }

    @Test fun file_names_and_fingerprints_are_ascii() {
        ascii(app.parley.common.calls.VoicemailFiles.shareName(2026, 9, 24, 14, 32, "audio/amr"))
        assertEquals("voicemail-2026-09-24-1432.amr", app.parley.common.calls.VoicemailFiles.shareName(2026, 9, 24, 14, 32, "audio/amr"))
        ascii(app.parley.common.spam.ListPack.sha256Hex(byteArrayOf(1, 2, 3)))
        ascii(app.parley.common.backup.RetentionDecider.fileName(java.time.Instant.ofEpochSecond(1_790_000_000), java.time.ZoneOffset.UTC))
    }
}

class TemporaryMergeTest {
    @Test fun merge_keeps_every_raw_and_the_earlier_expiry() {
        val (ids, at) = TemporaryExpiry.merge("12,15" to 500L, "15,20" to 300L)
        assertEquals(setOf(12L, 15L, 20L), TemporaryExpiry.decodeIds(ids))
        assertEquals(300L, at)
    }

    @Test fun merge_with_missing_ids_keeps_the_other() {
        assertEquals("7" to 100L, TemporaryExpiry.merge(null to 100L, "7" to Long.MAX_VALUE))
        assertEquals(null to 100L, TemporaryExpiry.merge(null to 100L, null to 200L))
    }

    @Test fun pending_key_is_recognisable() {
        val k = TemporaryExpiry.pendingKey(42)
        assertTrue(TemporaryExpiry.isPendingKey(k))
        assertFalse(TemporaryExpiry.isPendingKey("0r1-2A3B"))
    }
}

class ExactPurgeTest {
    @Test fun exact_match_needs_the_same_line() {
        assertTrue(PhoneNumbers.sameExact("+33 6 12 34 56 78", "06 12 34 56 78", "FR"))
        // Same last 9 digits, other country: `same` may say yes; a purge must not.
        assertFalse(PhoneNumbers.sameExact("+44 6 12 34 56 78", "+33 6 12 34 56 78", "FR"))
        assertFalse(PhoneNumbers.sameExact("1234", "01234", null))
        assertTrue(PhoneNumbers.sameExact("1234", "1234", null))
        assertFalse(PhoneNumbers.sameExact("", "", null))
    }
}

class VaultKeysFallbackTest {
    @Test fun stored_rows_include_a_last_digits_fallback() {
        val rows = VaultNumberKeys.storedWithFallback(listOf("06 12 34 56 78"), "FR")
        assertTrue(VaultNumberKeys.E164_PREFIX + "+33612345678" in rows)
        assertTrue(PhoneNumbers.matchKey("0612345678") in rows)
        // A call read under another region still finds a row through the non-exact lookup, never the exact one.
        val roaming = VaultNumberKeys.lookup("06 12 34 56 78", "DE")
        assertTrue(roaming.any { it in rows })
        assertFalse(VaultNumberKeys.lookup("06 12 34 56 78", "DE", exact = true).any { it in rows })
    }
}

class RegionPickTest {
    @Test fun sim_then_network_then_system_locales() {
        assertEquals("FR", RegionPick.pick("fr", "de", listOf("GB"), "US"))
        assertEquals("DE", RegionPick.pick("", "de", listOf("GB"), "US"))
        // An app language without a country ("ar") never decides; the system locale does.
        assertEquals("GB", RegionPick.pick(null, null, listOf("", "GB"), ""))
        assertEquals("", RegionPick.pick(null, null, emptyList(), ""))
        assertEquals("US", RegionPick.pick(null, "123", listOf(null), "us"))
    }
}

class DeferredKeyPressTest {
    private fun tracker() = app.parley.common.calls.KeyPressTracker(deferPress = true)
    private val press = app.parley.common.calls.KeyAction.Press

    @Test fun a_scroll_that_starts_on_a_key_sends_nothing() {
        val k = tracker()
        assertTrue(k.down(0).isEmpty())
        assertTrue(k.move(inside = true, now = 30, scrolled = true).isEmpty())
        assertTrue(k.settle(100).isEmpty())
        assertTrue(k.up(400).isEmpty())
        assertFalse(k.typedThisTouch)
    }

    @Test fun a_scroll_taking_over_cancels_without_pressing() {
        val k = tracker()
        k.down(0)
        assertTrue(k.cancel(50).isEmpty())
        assertFalse(k.typedThisTouch)
    }

    @Test fun a_quick_tap_presses_on_release() {
        val k = tracker()
        k.down(0)
        assertEquals(listOf(press, app.parley.common.calls.KeyAction.StopTone(150)), k.up(40))
        assertTrue(k.typedThisTouch)
    }

    @Test fun a_settled_touch_presses_then_holds_the_tone() {
        val k = tracker()
        k.down(0)
        assertEquals(listOf(press), k.settle(100))
        assertEquals(listOf(app.parley.common.calls.KeyAction.StopTone(0)), k.up(600))
    }

    @Test fun long_press_says_whether_this_touch_typed() {
        val immediate = app.parley.common.calls.KeyPressTracker()
        immediate.down(0)
        immediate.longPressDue(500)
        assertTrue(immediate.typedThisTouch)
        val cancelled = app.parley.common.calls.KeyPressTracker()
        cancelled.down(0)
        // A cancelled gesture stops the tone (the finally path) and never presses again.
        assertEquals(listOf(app.parley.common.calls.KeyAction.StopTone(140)), cancelled.cancel(10))
        assertTrue(cancelled.up(20).isEmpty())
    }
}
