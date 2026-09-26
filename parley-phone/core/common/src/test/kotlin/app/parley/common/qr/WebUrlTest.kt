package app.parley.common.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUrlTest {
    private fun p(url: String) = WebUrl.parse(url)!!
    private fun host(url: String) = WebUrl.parse(url)?.host

    @Test fun backslash_ends_the_host_like_a_browser() {
        val u = p("https://evil.com\\@paypal.com/login")
        assertEquals("evil.com", u.host)
        assertFalse(u.hadUserInfo)
        assertEquals("https://evil.com/@paypal.com/login", u.href)
        assertEquals("evil.com", host("https://evil.com\\\\paypal.com"))
        assertEquals("evil.com", host("https:\\\\evil.com\\path"))
        assertEquals("evil.com", host("https:/\\evil.com"))
        assertEquals("evil.com", host("https:evil.com"))
        assertEquals("/a/b/c", p("https://example.com\\a\\b/c").path)
    }

    @Test fun user_info_is_stripped_and_flagged() {
        val u = p("https://paypal.com@evil.com/x")
        assertEquals("evil.com", u.host)
        assertTrue(u.hadUserInfo)
        assertEquals("https://evil.com/x", u.href)
        assertEquals("evil.com", host("https://user:pass@evil.com"))
        assertEquals("evil.com", host("https://a@b@evil.com/"))
        // The user name ends at a `\` or `/` like the host does: "@" after it is path.
        assertEquals("a.com", host("https://a.com/@b.com"))
        // An encoded slash in the user name stays in the user name.
        assertEquals("paypal.com", host("https://evil.com%2F@paypal.com/"))
    }

    @Test fun encoded_or_forbidden_characters_in_the_host_are_refused() {
        assertNull(WebUrl.parse("https://paypal.com%2F.evil.com/"))
        assertNull(WebUrl.parse("https://paypal.com%5C.evil.com/"))
        assertNull(WebUrl.parse("https://paypal.com%40evil.com/"))
        assertNull(WebUrl.parse("https://pay pal.com/"))
        assertNull(WebUrl.parse("https://a..b.com/"))
        assertNull(WebUrl.parse("https:///"))
        assertNull(WebUrl.parse("https://example.com:abc/"))
        assertNull(WebUrl.parse("https://example.com:99999/"))
        assertNull(WebUrl.parse("https://%ZZ.com/"))
        assertNull(WebUrl.parse("ftp://example.com/"))
        assertNull(WebUrl.parse("javascript:alert(1)"))
        // Percent-encoded letters are just letters.
        assertEquals("paypal.com", host("https://p%61ypal.com/"))
    }

    @Test fun tabs_and_line_breaks_are_removed_everywhere() {
        assertEquals("paypal.com", host("https://pay\tpal.com/"))
        assertEquals("evil.com", host("ht\ttps://ev\nil.com\r/"))
        assertEquals("https://example.com/ab", p(" https://example.com/a\tb ").href)
    }

    @Test fun case_trailing_dots_and_default_ports() {
        val u = p("HTTPS://WWW.PayPal.COM./Login?A=B#C")
        assertEquals("https", u.scheme)
        assertEquals("www.paypal.com", u.host)
        assertEquals("https://www.paypal.com/Login?A=B#C", u.href)
        assertNull(p("https://example.com:443/").port)
        assertNull(p("http://example.com:80").port)
        assertEquals(8080, p("https://example.com:8080/").port)
        assertEquals("https://example.com:8080/", p("https://example.com:08080").href)
        assertEquals("https://example.com/", p("https://example.com").href)
    }

    @Test fun idn_hosts_become_punycode() {
        assertEquals("xn--mnchen-3ya.de", host("https://münchen.de/"))
        assertEquals("xn--mnchen-3ya.de", host("https://MÜNCHEN.de/"))
        assertEquals("xn--mnchen-3ya.de", host("https://m%C3%BCnchen.de/"))
        assertEquals("xn--mnchen-3ya.de", host("https://xn--mnchen-3ya.de/"))
        // Cyrillic "а" in "аpple.com" is its own punycode domain, not apple.com.
        assertTrue(host("https://аpple.com")!!.startsWith("xn--"))
    }

    @Test fun ip_addresses_in_every_browser_form() {
        assertEquals("127.0.0.1", host("http://127.0.0.1/"))
        assertEquals("127.0.0.1", host("http://0x7f.1/"))
        assertEquals("127.0.0.1", host("http://2130706433/"))
        assertEquals("127.0.0.1", host("http://0177.0.0.1/"))
        assertEquals("192.168.0.1", host("http://192.168.1/"))
        assertNull(WebUrl.parse("http://1.2.3.256/"))
        assertNull(WebUrl.parse("http://example.123/"))
        assertTrue(p("http://0x7f.1/").isIp)
        val v6 = p("https://[2001:DB8::1]:8443/x")
        assertEquals("[2001:db8::1]", v6.host)
        assertEquals(8443, v6.port)
        assertTrue(v6.isIp)
        assertNull(WebUrl.parse("https://[evil.com]/"))
        assertNull(WebUrl.parse("https://[::1/"))
    }

    @Test fun path_query_and_fragment_are_encoded_not_reinterpreted() {
        assertEquals("https://example.com/a%20b?q=%22x%22#f%20g", p("https://example.com/a b?q=\"x\"#f g").href)
        assertEquals("https://example.com/%C3%A9?%C3%A9", p("https://example.com/é?é").href)
        // A `\` after the path is part of the query, as in a browser.
        assertEquals("q=a\\b", p("https://example.com/?q=a\\b").query)
        assertEquals("/@alice:matrix.org", p("https://matrix.to/#/@alice:matrix.org").fragment)
        assertEquals("/#room:matrix.org", p("https://matrix.to/#/#room:matrix.org").fragment)
    }
}
