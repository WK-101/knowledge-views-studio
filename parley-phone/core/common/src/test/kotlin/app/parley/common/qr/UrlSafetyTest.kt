package app.parley.common.qr

import app.parley.common.photo.PhotoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSafetyTest {
    private fun w(url: String) = UrlSafety.analyse(url)!!.warnings

    @Test fun plain_https_is_quiet() {
        val i = UrlSafety.analyse("https://www.example.co.uk/a?b")!!
        assertEquals("example.co.uk", i.domain)
        assertTrue(i.warnings.isEmpty())
        assertFalse(i.isRisky)
    }

    @Test fun idn_is_shown_readable_with_punycode_beside() {
        val i = UrlSafety.analyse("https://xn--mnchen-3ya.de/")!!
        assertEquals("münchen.de", i.displayHost)
        assertEquals("xn--mnchen-3ya.de", i.asciiHost)
        assertTrue(UrlSafety.Warning.IDN in i.warnings)
        assertFalse(i.isRisky)
    }

    @Test fun cyrillic_lookalikes() {
        // "аpple.com" with a Cyrillic а.
        assertTrue(UrlSafety.Warning.MIXED_SCRIPT in w("https://аpple.com"))
        // All-Cyrillic letters that look Latin.
        assertTrue(UrlSafety.Warning.MIXED_SCRIPT in w("https://аррӏе.com"))
        assertTrue(UrlSafety.analyse("https://аpple.com")!!.isRisky)
        // Real Cyrillic words aren't flagged.
        assertFalse(UrlSafety.Warning.MIXED_SCRIPT in w("https://яндекс.рф"))
    }

    @Test fun brand_in_front_of_someone_elses_domain() {
        assertTrue(UrlSafety.Warning.LOOKALIKE in w("https://paypal.com.secure-login.io/"))
        assertTrue(UrlSafety.Warning.LOOKALIKE in w("https://paypa1.com/"))
        assertFalse(UrlSafety.Warning.LOOKALIKE in w("https://www.paypal.com/"))
        assertFalse(UrlSafety.Warning.LOOKALIKE in w("https://mail.google.com/"))
    }

    @Test fun shorteners_userinfo_ip_http() {
        assertTrue(UrlSafety.Warning.SHORTENER in w("https://bit.ly/abc"))
        assertTrue(UrlSafety.Warning.USERINFO in w("https://www.bank.com@evil.example/"))
        assertEquals("evil.example", UrlSafety.analyse("https://www.bank.com@evil.example/")!!.domain)
        assertTrue(UrlSafety.Warning.IP_ADDRESS in w("http://192.168.0.1/login"))
        assertTrue(UrlSafety.Warning.NOT_HTTPS in w("http://example.com"))
        assertNull(UrlSafety.analyse("ftp://example.com"))
    }

    @Test fun text_cleaning() {
        assertEquals("abc", QrText.clean("a‮b⁦c"))
        assertEquals("a\nb", QrText.clean("a\r\nb\u0007"))
        assertEquals("a b", QrText.clean("a\nb", keepLines = false))
        assertTrue(QrText.hasHidden("x‮y"))
        assertEquals("ab…", QrText.shown("abcdef", max = 2))
        assertEquals("é 😀", QrText.percentDecode("%C3%A9 😀"))
        assertEquals("100%", QrText.percentDecode("100%"))
    }

    @Test fun photo_sizes_for_scanning() {
        assertEquals(1600 to 1200, PhotoMath.fitLongSide(4000, 3000, 1600))
        assertEquals(1200 to 1600, PhotoMath.fitLongSide(3000, 4000, 1600))
        assertEquals(500 to 400, PhotoMath.fitLongSide(500, 400, 1600))
        assertEquals(listOf(1600, 3000, 800), PhotoMath.qrScanSizes(4000, 3000))
        assertEquals(listOf(1000, 800), PhotoMath.qrScanSizes(1000, 700))
        assertEquals(listOf(500), PhotoMath.qrScanSizes(500, 300))
    }
}

class ScanQrSettingTest {
    @Test fun scan_qr_is_in_settings_search() {
        val e = app.parley.common.SettingsCatalog.entries.first { it.key == "scan_qr" }
        assertEquals(app.parley.common.SettingsCategory.CONTACTS, e.category)
        assertTrue("qr" in e.keywords)
    }
}
