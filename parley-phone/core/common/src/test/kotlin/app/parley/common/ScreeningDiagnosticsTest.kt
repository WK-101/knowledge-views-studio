package app.parley.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreeningDiagnosticsTest {
    @Test fun report_has_flags_and_counts_but_no_personal_data() {
        // Regression: the diagnostics report printed the whole settings object (numbers, reply text, ringtone URIs).
        val s = ScreeningSettings(
            blockHidden = true,
            emergencyExtras = listOf("+33612345678", "0147200001"),
            busyReplyText = "Call me on 0612345678 later",
            repeatRingtone = "content://media/internal/audio/media/1234567",
            likelySpamRingtone = "content://media/external/audio/media/7654321",
            webSearchUrl = "https://search.example/?user=9876543&q=",
            snoozeUntil = 1_700_000_000_000,
            offHours = OffHours(enabled = true, allow = OffHoursAllow.LABEL, labelId = 1234567, labelTitle = "Dr 0600000000"),
        )
        val text = ScreeningDiagnostics.describe(s)
        assertFalse(text, Regex("\\d{6,}").containsMatchIn(text))
        assertFalse(text.contains("content://"))
        assertFalse(text.contains("Call me"))
        assertTrue(text.contains("blockHidden=true"))
        assertTrue(text.contains("emergencyExtras=2"))
    }
}
