package app.parley.common.people

import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandlesTest {
    @Test fun im_rows_map_to_services_and_back() {
        assertEquals(HandleService.XMPP, Handles.fromIm(7, null))
        assertEquals(HandleService.SKYPE, Handles.fromIm(3, null))
        assertEquals(HandleService.MATRIX, Handles.fromIm(-1, "Matrix"))
        assertEquals(HandleService.THREEMA, Handles.fromIm(-1, "threema"))
        assertEquals(HandleService.TELEGRAM, Handles.fromIm(-1, "Telegram username"))
        assertEquals(HandleService.OTHER, Handles.fromIm(-1, "Jami"))
        assertEquals(HandleService.OTHER, Handles.fromIm(42, null))

        val jami = Handles.fromRow(Mime.IM, "ring:abc", "-1", "Jami")!!
        assertEquals("Jami", jami.serviceLabel)
        // An unknown service keeps its own protocol name when written back.
        assertEquals("Jami", Handles.toColumns(jami).second["data6"])

        val (mime, cols) = Handles.toColumns(Handle(HandleService.MATRIX, " @anna:matrix.org "))
        assertEquals(Mime.IM, mime)
        assertEquals(mapOf("data1" to "@anna:matrix.org", "data5" to "-1", "data6" to "Matrix"), cols)
        assertEquals(Mime.SIP to mapOf("data1" to "anna@sip.example.com"), Handles.toColumns(Handle(HandleService.SIP, "sip:anna@sip.example.com")))
        assertEquals(HandleService.SIP, Handles.fromRow(Mime.SIP, "anna@x.org", null, null)!!.service)
        assertNull(Handles.fromRow(Mime.PHONE, "123", null, null))
    }

    @Test fun handle_links_use_app_schemes_and_mark_web_links() {
        val tg = Handles.link(Handle(HandleService.TELEGRAM, "@anna_smith"))!!
        assertEquals("tg://resolve?domain=anna_smith", tg.uri)
        assertTrue("org.telegram.messenger" in tg.packages)
        assertFalse(tg.isWeb)
        assertEquals("threema://compose?id=ABCD1234", Handles.link(Handle(HandleService.THREEMA, "abcd1234"))!!.uri)
        val mx = Handles.link(Handle(HandleService.MATRIX, "@anna:matrix.org"))!!
        assertEquals("https://matrix.to/#/@anna:matrix.org", mx.uri)
        assertTrue("A matrix.to link must never open a browser without asking", mx.isWeb)
        assertEquals("sgnl://signal.me/#u/anna.01", Handles.link(Handle(HandleService.SIGNAL, "anna.01"))!!.uri)
        assertEquals("xmpp:anna@jabber.org", Handles.link(Handle(HandleService.XMPP, "xmpp:anna@jabber.org"))!!.uri)
        assertTrue(Handles.link(Handle(HandleService.SIP, "anna@sip.example.com"))!!.isCall)
        assertNull("No link for a malformed handle", Handles.link(Handle(HandleService.MATRIX, "anna")))
        assertNull(Handles.link(Handle(HandleService.DISCORD, "anna")))
        assertNull(Handles.link(Handle(HandleService.OTHER, "whatever")))
    }

    @Test fun handle_hints_explain_malformed_values() {
        assertNotNull(Handles.problem(HandleService.MATRIX, "anna"))
        assertNull(Handles.problem(HandleService.MATRIX, "@anna:matrix.org"))
        assertNotNull(Handles.problem(HandleService.THREEMA, "ABC"))
        assertNull(Handles.problem(HandleService.SIGNAL, "anna.42"))
        assertNotNull(Handles.problem(HandleService.SIGNAL, "anna"))
        assertNull(Handles.problem(HandleService.TELEGRAM, ""))
    }

    @Test fun editing_handles_never_touches_other_data_rows() {
        val before = listOf(
            RowEdits.Row(1, Mime.PHONE, mapOf("data1" to "+441234")),
            RowEdits.Row(2, Mime.EMAIL, mapOf("data1" to "a@x.org")),
            RowEdits.Row(3, "vnd.android.cursor.item/vnd.com.whatsapp.profile", mapOf("data1" to "123@s.whatsapp.net")),
            RowEdits.Row(4, Mime.IM, mapOf("data1" to "@anna:matrix.org", "data5" to "-1", "data6" to "Matrix")),
            RowEdits.Row(5, Mime.IM, mapOf("data1" to "anna@jabber.org", "data5" to "7", "data6" to null)),
            RowEdits.Row(6, Mime.SIP, mapOf("data1" to "anna@sip.x")),
            RowEdits.Row(7, Mime.IM, mapOf("data1" to "locked", "data5" to "-1", "data6" to "Wire")),
        )
        val kinds = setOf(Mime.IM, Mime.SIP)
        fun row(id: Long?, h: Handle) = Handles.toColumns(h).let { (m, v) -> RowEdits.Row(id, m, v) }
        // Keep Matrix unchanged, change XMPP, drop SIP and the locked row, add Threema; also pass a stray phone id.
        val after = listOf(
            row(4, Handle(HandleService.MATRIX, "@anna:matrix.org")),
            row(5, Handle(HandleService.XMPP, "anna@conversations.im")),
            row(null, Handle(HandleService.THREEMA, "ABCD1234")),
            RowEdits.Row(1, Mime.PHONE, mapOf("data1" to "changed")),
        )
        val ops = RowEdits.plan(before, after, kinds, locked = setOf(7))
        assertEquals(
            setOf(
                RowEdits.Op.Delete(6, Mime.SIP),
                RowEdits.Op.Update(5, Mime.IM, mapOf("data1" to "anna@conversations.im", "data5" to "7", "data6" to null)),
                RowEdits.Op.Insert(Mime.IM, mapOf("data1" to "ABCD1234", "data5" to "-1", "data6" to "Threema")),
            ),
            ops.toSet(),
        )
        assertTrue("Phone, e-mail and messenger rows are never touched", ops.none { it.mime !in kinds })
        assertTrue("Read-only rows are never deleted", ops.none { it is RowEdits.Op.Delete && it.id == 7L })
        assertTrue("An unchanged handle isn't rewritten", ops.none { (it as? RowEdits.Op.Update)?.id == 4L })
    }

    @Test fun changing_a_handle_kind_replaces_the_row() {
        val before = listOf(RowEdits.Row(5, Mime.IM, mapOf("data1" to "anna@x.org", "data5" to "7")))
        val after = listOf(RowEdits.Row(5, Mime.SIP, mapOf("data1" to "anna@x.org")))
        assertEquals(
            listOf(RowEdits.Op.Delete(5, Mime.IM), RowEdits.Op.Insert(Mime.SIP, mapOf("data1" to "anna@x.org"))),
            RowEdits.plan(before, after, setOf(Mime.IM, Mime.SIP)),
        )
    }
}
