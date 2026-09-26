package app.parley.common.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One row per link form in the research table (B2). */
class MessengerQrTest {
    private fun m(text: String): QrPayload.Messenger {
        val p = QrParser.parse(text)
        assertTrue("Expected a messenger link for $text but got $p", p is QrPayload.Messenger)
        return p as QrPayload.Messenger
    }

    private fun check(text: String, app: QrApp, kind: LinkKind, handle: String? = null, phone: String? = null) {
        val r = m(text)
        assertEquals(text, app, r.app)
        assertEquals(text, kind, r.kind)
        if (handle != null) assertEquals(text, handle, r.handle)
        if (phone != null) assertEquals(text, phone, r.phone)
    }

    @Test fun whatsapp() {
        check("https://wa.me/491511234567", QrApp.WHATSAPP, LinkKind.PHONE, phone = "+491511234567")
        check("https://wa.me/491511234567?text=Hi", QrApp.WHATSAPP, LinkKind.PHONE, phone = "+491511234567")
        check("https://wa.me/qr/ABCDEFG123", QrApp.WHATSAPP, LinkKind.PROFILE)
        check("https://wa.me/message/XYZ", QrApp.WHATSAPP, LinkKind.PROFILE)
        check("https://chat.whatsapp.com/InviteCode123", QrApp.WHATSAPP, LinkKind.GROUP)
        check("https://whatsapp.com/channel/0029Va", QrApp.WHATSAPP, LinkKind.CHANNEL)
        check("https://api.whatsapp.com/send?phone=15551234567", QrApp.WHATSAPP, LinkKind.PHONE, phone = "+15551234567")
        check("whatsapp://send?phone=15551234567", QrApp.WHATSAPP, LinkKind.PHONE, phone = "+15551234567")
    }

    @Test fun signal() {
        check("https://signal.me/#p/+15551234567", QrApp.SIGNAL, LinkKind.PHONE, phone = "+15551234567")
        check("https://signal.me/#eu/abcDEF_-123", QrApp.SIGNAL, LinkKind.PROFILE)
        check("https://signal.group/#CjQKIA", QrApp.SIGNAL, LinkKind.GROUP)
        val s = m("sgnl://signal.me/#p/+15551234567")
        assertEquals(LinkKind.PHONE, s.kind)
        assertEquals("sgnl://signal.me/#p/+15551234567", s.uri)
        assertFalse(s.hasWebPage)
    }

    @Test fun telegram() {
        check("https://t.me/durov", QrApp.TELEGRAM, LinkKind.PROFILE, handle = "@durov")
        check("https://t.me/+491511234567", QrApp.TELEGRAM, LinkKind.PHONE, phone = "+491511234567")
        check("https://t.me/+AbCdEfGh123", QrApp.TELEGRAM, LinkKind.GROUP)
        check("https://t.me/joinchat/AbCdEf", QrApp.TELEGRAM, LinkKind.GROUP)
        check("https://t.me/contact/abc123", QrApp.TELEGRAM, LinkKind.PROFILE)
        check("https://telegram.me/somechannel/42", QrApp.TELEGRAM, LinkKind.CHANNEL, handle = "@somechannel")
        check("tg://resolve?domain=durov", QrApp.TELEGRAM, LinkKind.PROFILE, handle = "@durov")
        check("tg://resolve?phone=491511234567", QrApp.TELEGRAM, LinkKind.PHONE, phone = "+491511234567")
        check("tg://join?invite=AbCd", QrApp.TELEGRAM, LinkKind.GROUP)
        check("tg://contact?token=xyz", QrApp.TELEGRAM, LinkKind.PROFILE)
    }

    @Test fun wechat_line_viber() {
        check("https://u.wechat.com/AbCd", QrApp.WECHAT, LinkKind.PROFILE)
        check("https://weixin.qq.com/r/AbCd", QrApp.WECHAT, LinkKind.PROFILE)
        assertTrue(QrApp.WECHAT.scanInside)
        check("https://line.me/R/ti/p/~myline", QrApp.LINE, LinkKind.PROFILE, handle = "~myline")
        check("https://line.me/ti/p/AbC123", QrApp.LINE, LinkKind.PROFILE)
        check("https://line.me/ti/g/AbC", QrApp.LINE, LinkKind.GROUP)
        check("https://lin.ee/AbC", QrApp.LINE, LinkKind.PROFILE)
        check("viber://chat?number=%2B491511234567", QrApp.VIBER, LinkKind.PHONE, phone = "+491511234567")
        check("viber://add?number=491511234567", QrApp.VIBER, LinkKind.PHONE, phone = "+491511234567")
        check("https://invite.viber.com/?g2=AQB", QrApp.VIBER, LinkKind.GROUP)
    }

    @Test fun threema_skype_messenger_instagram_snapchat() {
        val t = m("3mid:ECHOECHO,0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        assertEquals(QrApp.THREEMA, t.app)
        assertEquals("ECHOECHO", t.handle)
        assertEquals("https://threema.id/ECHOECHO", t.uri)
        check("https://threema.id/ECHOECHO", QrApp.THREEMA, LinkKind.ID, handle = "ECHOECHO")
        check("threema://add?id=ECHOECHO", QrApp.THREEMA, LinkKind.ID, handle = "ECHOECHO")
        check("skype:echo123?call", QrApp.SKYPE, LinkKind.PROFILE, handle = "echo123")
        assertTrue(QrApp.SKYPE.legacy)
        check("https://m.me/someone", QrApp.MESSENGER, LinkKind.PROFILE, handle = "someone")
        check("https://instagram.com/natgeo", QrApp.INSTAGRAM, LinkKind.PROFILE, handle = "@natgeo")
        check("https://www.instagram.com/_u/natgeo", QrApp.INSTAGRAM, LinkKind.PROFILE, handle = "@natgeo")
        check("https://instagram.com/p/Cxyz", QrApp.INSTAGRAM, LinkKind.LINK)
        check("https://snapchat.com/add/someone", QrApp.SNAPCHAT, LinkKind.PROFILE, handle = "someone")
    }

    @Test fun kakao_zalo_discord() {
        check("https://open.kakao.com/o/gAbCdEf", QrApp.KAKAOTALK, LinkKind.GROUP)
        check("https://qr.kakao.com/talk/AbC", QrApp.KAKAOTALK, LinkKind.PROFILE)
        check("https://zalo.me/0912345678", QrApp.ZALO, LinkKind.PHONE, phone = "+84912345678")
        check("https://zalo.me/g/abcxyz", QrApp.ZALO, LinkKind.GROUP)
        check("https://zalo.me/someid", QrApp.ZALO, LinkKind.PROFILE)
        check("https://discord.gg/abcDEF", QrApp.DISCORD, LinkKind.INVITE)
        check("https://discord.com/invite/abcDEF", QrApp.DISCORD, LinkKind.INVITE)
        check("https://discord.com/users/123456789", QrApp.DISCORD, LinkKind.PROFILE)
    }

    @Test fun session_simplex_matrix_wire_briar() {
        val id = "05" + "a".repeat(64)
        check(id, QrApp.SESSION, LinkKind.ID, handle = id)
        assertTrue(QrApp.SESSION.pasteOnly)
        check("simplex:/contact#/?v=2-7&smp=smp%3A%2F%2Fx", QrApp.SIMPLEX, LinkKind.PROFILE)
        check("https://simplex.chat/invitation#/?v=2-7&smp=x", QrApp.SIMPLEX, LinkKind.INVITE)
        check("simplex:/a#abcdef?h=smp1.simplex.im", QrApp.SIMPLEX, LinkKind.PROFILE)
        check("simplex:/g#abcdef", QrApp.SIMPLEX, LinkKind.GROUP)
        check("https://smp8.simplex.im/i#abcdef", QrApp.SIMPLEX, LinkKind.INVITE)
        check("https://matrix.to/#/@alice:matrix.org", QrApp.MATRIX, LinkKind.PROFILE, handle = "@alice:matrix.org")
        check("https://matrix.to/#/#room:matrix.org", QrApp.MATRIX, LinkKind.GROUP, handle = "#room:matrix.org")
        check("matrix:u/alice:matrix.org?action=chat", QrApp.MATRIX, LinkKind.PROFILE, handle = "@alice:matrix.org")
        check("https://account.wire.com/user-profile/?id=0f5c7c", QrApp.WIRE, LinkKind.PROFILE)
        check("briar://aabbccddeeff", QrApp.BRIAR, LinkKind.INVITE)
        assertTrue(QrApp.BRIAR.pasteOnly)
    }

    @Test fun look_alikes_are_not_messengers() {
        assertTrue(QrParser.parse("https://wa.me.evil.example/491511234567") is QrPayload.Url)
        assertTrue(QrParser.parse("https://nott.me/durov") is QrPayload.Url)
        assertTrue(QrParser.parse("05abc") is QrPayload.Text)
    }

    @Test fun messenger_hosts_are_read_like_a_browser() {
        // The browser goes to evil.com here: not a Telegram link, and UrlSafety shows evil.com.
        val a = QrParser.parse("https://evil.com\\@t.me/joinchat/x")
        assertTrue(a is QrPayload.Url)
        assertEquals("evil.com", (a as QrPayload.Url).info.domain)
        val b = QrParser.parse("https://t.me@evil.com/joinchat/x")
        assertTrue(b is QrPayload.Url)
        assertEquals("evil.com", (b as QrPayload.Url).info.domain)
        // Backslashes in the path are slashes; the link opened is the normalised one.
        assertEquals("https://t.me/joinchat/x", m("https://T.ME\\joinchat\\x").uri)
        assertEquals(LinkKind.GROUP, m("https://T.ME\\joinchat\\x").kind)
        assertEquals("https://t.me/durov", m("https://t.me.:443/durov").uri)
    }

    @Test fun http_links_open_as_https() {
        assertEquals("https://t.me/durov", m("http://t.me/durov").uri)
    }

    @Test fun invalid_numbers_are_not_phone_links() {
        val r = m("https://wa.me/12")
        assertEquals(LinkKind.LINK, r.kind)
        assertNull(r.phone)
    }

    @Test fun every_app_has_a_package() {
        QrApp.entries.forEach { assertTrue(it.name, it.packages.isNotEmpty()) }
        assertTrue(QrApp.entries.size >= 19)
    }
}
