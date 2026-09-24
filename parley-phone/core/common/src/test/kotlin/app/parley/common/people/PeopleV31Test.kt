package app.parley.common.people

import app.parley.common.MessengerApp
import app.parley.common.SettingsCatalog
import app.parley.common.SettingsSearch
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleV31Test {
    // ---------------------------------------------------------------- I1 handles

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

    // ---------------------------------------------------------------- M6 / M7 preferred messenger

    @Test fun preferred_messenger_reads_the_old_value_and_writes_it_back_unchanged() {
        val legacy = MessengerPrefs.decode("com.whatsapp")
        assertEquals(MessengerPrefs(call = "com.whatsapp"), legacy)
        assertEquals("com.whatsapp", legacy.encode())
        val full = MessengerPrefs(call = "com.whatsapp", message = "org.thoughtcrime.securesms", video = "com.whatsapp", number = "+44 7700;900")
        assertEquals(full, MessengerPrefs.decode(full.encode()))
        assertNull(MessengerPrefs().encode())
        assertEquals(MessengerPrefs(), MessengerPrefs.decode(null))
    }

    @Test fun message_route_follows_links_before_data_rows_are_needed() {
        val numbers = listOf("+447700900123", "+441234567890")
        val wa = MessengerPrefs(message = "com.whatsapp")
        // WhatsApp hasn't linked the person (no data row) but is installed: open it by number.
        assertEquals(
            MessageRoute.MessengerLink(MessengerApp.WHATSAPP, "+447700900123"),
            MessageRoutes.plan(wa, linked = emptySet(), installed = setOf("com.whatsapp"), numbers = numbers, defaultNumber = numbers[0]),
        )
        assertEquals(MessageRoute.MessengerRow("com.whatsapp"), MessageRoutes.plan(wa, setOf("com.whatsapp"), setOf("com.whatsapp"), numbers, numbers[0]))
        assertEquals(MessageRoute.Ask, MessageRoutes.plan(wa, emptySet(), emptySet(), numbers, numbers[0]))
        assertEquals(MessageRoute.Ask, MessageRoutes.plan(MessengerPrefs(), emptySet(), setOf("com.whatsapp"), numbers, numbers[0]))
        val sms = MessengerPrefs(message = MessengerPrefs.SMS, number = "+441234567890")
        assertEquals(MessageRoute.Sms("+441234567890"), MessageRoutes.plan(sms, emptySet(), emptySet(), numbers, numbers[0]))
        // A remembered number the contact no longer has falls back to the default one.
        assertEquals(MessageRoute.Sms("+447700900123"), MessageRoutes.plan(sms.copy(number = "+15550000000"), emptySet(), emptySet(), numbers, numbers[0]))
        assertTrue(MessageRoutes.showUnlinkedHint("com.whatsapp", emptySet(), setOf("com.whatsapp")))
        assertFalse(MessageRoutes.showUnlinkedHint("com.whatsapp", setOf("com.whatsapp"), setOf("com.whatsapp")))
    }

    // ---------------------------------------------------------------- I4 other fields

    @Test fun other_fields_show_unknown_kinds_and_skip_known_ones() {
        fun r(mime: String, vararg v: Pair<String, String?>) = DataRow(mime, v.toMap())
        val rows = listOf(
            r(Mime.PHONE, "data1" to "123"),
            r(Mime.IM, "data1" to "x@y"),
            r(OtherFields.GOOGLE_FILE_AS, "data1" to "Smith, Anna"),
            r(OtherFields.GOOGLE_USER_FIELD, "data1" to "Shoe size", "data2" to "38"),
            r("vnd.android.cursor.item/vnd.example.pet_name", "data1" to "", "data2" to "Rex"),
            r("vnd.android.cursor.item/vnd.com.whatsapp.profile", "data1" to "123@s.whatsapp.net"),
            r("vnd.android.cursor.item/vnd.example.empty"),
        )
        val fields = OtherFields.describe(rows) { it.mimeType.contains("whatsapp") }
        assertEquals(
            listOf("File as" to "Smith, Anna", "Shoe size" to "38", "Pet name" to "Rex"),
            fields.map { it.label to it.value },
        )
    }

    // ---------------------------------------------------------------- I5 relation types

    @Test fun relation_types_map_to_android_and_back() {
        assertTrue(RelationTypes.all.size >= 60)
        assertEquals(RelationTypes.all.size, RelationTypes.all.map { it.key }.distinct().size)
        assertEquals("spouse", RelationTypes.fromAndroid(14, null)!!.key)
        assertEquals("co-worker", RelationTypes.fromAndroid(0, "Co-worker")!!.key)
        assertEquals("neighbor", RelationTypes.fromAndroid(0, "neighbour")!!.key)
        assertNull(RelationTypes.fromAndroid(0, "Fishing buddy"))
        assertEquals(14 to null, RelationTypes.toAndroid(RelationTypes.byKey("spouse")!!))
        assertEquals(0 to "Grandmother", RelationTypes.toAndroid(RelationTypes.byKey("grandmother")!!))
        assertEquals("grandmother", RelationTypes.search("grandm").first().key)
    }

    @Test fun a_picked_relation_contact_wins_over_name_matching() {
        val contacts = listOf(Triple(1L, "Anna", "k1"), Triple(2L, "Anna", "k2"))
        val picked = mapOf("anna" to RelationLinks.Link("k2", 2))
        assertEquals(picked, RelationLinks.update(listOf("Anna"), emptyMap(), contacts, self = 9, picked = picked))
        assertEquals(emptyMap<String, RelationLinks.Link>(), RelationLinks.update(listOf("Anna"), emptyMap(), contacts, self = 9))
    }

    // ---------------------------------------------------------------- I8 broad search

    @Test fun broad_search_says_which_field_matched() {
        val extra = BroadSearch.Extra(company = "Acme", addresses = listOf("12 Rue de la Paix, Paris"), note = "Met at the café", websites = listOf("anna.dev"), handles = listOf("@anna:matrix.org"))
        fun m(q: String) = BroadSearch.match(q, "Anna Smith", listOf("+44 7700 900123"), listOf("anna@x.org"), extra)
        assertEquals(BroadSearch.Field.NAME, m("smith"))
        assertEquals(BroadSearch.Field.NUMBER, m("7700"))
        assertEquals(BroadSearch.Field.EMAIL, m("x.org"))
        assertEquals(BroadSearch.Field.COMPANY, m("acme"))
        assertEquals(BroadSearch.Field.ADDRESS, m("paix"))
        assertEquals(BroadSearch.Field.NOTE, m("cafe"))
        assertEquals(BroadSearch.Field.WEBSITE, m("anna.dev"))
        assertEquals(BroadSearch.Field.HANDLE, m("matrix"))
        assertNull(m("zzz"))
        assertEquals("Matched: address", BroadSearch.hint(BroadSearch.Field.ADDRESS))
        assertNull(BroadSearch.hint(BroadSearch.Field.NAME))
    }

    // ---------------------------------------------------------------- I2 Me card

    @Test fun me_card_merges_parley_and_profile_and_makes_a_vcard() {
        val own = MeCards.fromMyDetails("Anna Maria Smith", "+44 7700 900123")
        val profile = MeCard(name = "A. Smith", phones = listOf("+447700900123", "+441234"), emails = listOf("Anna@X.org"), company = "Acme")
        val m = MeCards.merge(own, profile)
        assertEquals("Anna Maria Smith", m.name)
        assertEquals(listOf("+44 7700 900123", "+441234"), m.phones)
        assertEquals("Acme", m.company)
        val v = MeCards.vcard(m.copy(note = "secret"), setOf(MeCards.Part.NAME, MeCards.Part.PHONES))
        assertTrue(v.contains("N:Smith;Anna Maria;;;"))
        assertTrue(v.contains("TEL;TYPE=CELL:+44 7700 900123"))
        assertFalse("Only the chosen parts are shared", v.contains("Acme"))
        assertFalse("The private note is never shared", v.contains("secret"))
        assertEquals("a\\,b\\;c", MeCards.esc("a,b;c"))
        assertTrue(MeCard().isEmpty)
    }

    // ---------------------------------------------------------------- I6 caller card

    @Test fun caller_card_lines_respect_discreet_mode() {
        assertEquals("Plumber · Acme", CallerCard.subtitle("Plumber", "Acme"))
        assertEquals("Acme", CallerCard.subtitle(" ", "Acme"))
        assertNull(CallerCard.subtitle(null, ""))
        assertEquals("Fixed the boiler", CallerCard.context("  Fixed \n the   boiler "))
        assertEquals(10, CallerCard.context("x".repeat(50), 10)!!.length)
        assertNull(CallerCard.missedCallLine(isPrivate = true, hideVault = true, subtitle = "Acme", context = "Plumber"))
        assertEquals("Plumber", CallerCard.missedCallLine(isPrivate = true, hideVault = false, subtitle = "Acme", context = "Plumber"))
        assertEquals("Acme", CallerCard.missedCallLine(isPrivate = false, hideVault = true, subtitle = "Acme", context = null))
    }

    // ---------------------------------------------------------------- I7 directory

    @Test fun directory_trusts_the_caller_parameter_only_from_the_contacts_provider() {
        val cp2 = "com.android.providers.contacts"
        assertEquals("com.car.dialer", DirectoryPolicy.effectiveCaller(cp2, cp2, "com.car.dialer"))
        assertNull(DirectoryPolicy.effectiveCaller(cp2, cp2, null))
        // Any other app naming someone else is still itself.
        assertEquals("com.evil", DirectoryPolicy.effectiveCaller("com.evil", cp2, "com.car.dialer"))
        assertNull(DirectoryPolicy.effectiveCaller(null, cp2, "x"))
        assertEquals(DirectoryPolicy.Request.DIRECTORIES, DirectoryPolicy.request(listOf("directories")))
        assertEquals(DirectoryPolicy.Request.PHONE_LOOKUP, DirectoryPolicy.request(listOf("phone_lookup", "+447700900123")))
        assertEquals(DirectoryPolicy.Request.OTHER, DirectoryPolicy.request(listOf("contacts", "filter", "a")))
        assertEquals(DirectoryPolicy.Request.OTHER, DirectoryPolicy.request(listOf("phone_lookup")))
    }

    // ---------------------------------------------------------------- U4 / U6 / U10 / U11

    @Test fun swipe_actions_are_off_by_default() {
        val c = SwipeConfig()
        assertEquals(SwipeAction.NONE, c.action(true))
        val on = c.copy(enabled = true)
        assertEquals(SwipeAction.CALL, on.action(true))
        assertEquals(SwipeAction.MESSAGE, on.action(false))
        assertFalse(on.available(SwipeAction.CALL, hasNumber = false, canDelete = true))
        assertTrue(on.available(SwipeAction.DELETE, hasNumber = false, canDelete = true))
        assertEquals(SwipeAction.BLOCK, SwipeAction.parse("BLOCK", SwipeAction.NONE))
        assertEquals(SwipeAction.CALL, SwipeAction.parse("nonsense", SwipeAction.CALL))
    }

    @Test fun emoji_names_become_emoji_avatars() {
        assertEquals("🐶", AvatarText.leadingEmoji("🐶 Rex"))
        assertEquals("☕", AvatarText.leadingEmoji("☕ Café"))
        assertNull(AvatarText.leadingEmoji("Anna"))
        assertNull(AvatarText.leadingEmoji("  "))
        assertNull(AvatarText.leadingEmoji("123"))
    }

    @Test fun crash_reports_and_raw_dumps_are_masked() {
        val c = Reports.Crash(0, "main", "java.lang.IllegalStateException: bad number +44 7700 900123 for anna@x.org", "3.1", "15")
        val text = Reports.crashText(c)
        assertFalse(text.contains("7700 900123"))
        assertFalse(text.contains("anna@"))
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(Reports.crashText(c, mask = false).contains("+44 7700 900123"))
        assertEquals("Aaaa +99 9900", Reports.shape("Anna +44 7700"))
        assertEquals("null", Reports.shape(null))
        assertTrue(Reports.shape("x".repeat(100), 10).startsWith("aaaaaaaaaa…"))
        assertEquals(3, Reports.trimStack((1..10).joinToString("\n"), 2).lines().size)
    }

    @Test fun contacts_copy_as_plain_text() {
        val t = Reports.contactsAsText(
            listOf(
                Reports.TextContact("Anna", listOf("+44 7700" to "Mobile", "123" to null), listOf("a@x.org")),
                Reports.TextContact("Ben", emptyList(), emptyList()),
            ),
        )
        assertEquals("Anna\nMobile: +44 7700\n123\na@x.org\n\nBen", t)
    }

    @Test fun new_settings_are_searchable() {
        listOf("swipe_actions", "avatar_style", "private_directory", "crash_reports", "my_details").forEach { SettingsCatalog[it] }
        assertEquals("swipe_actions", SettingsSearch.search("swipe").first().key)
        assertEquals("crash_reports", SettingsSearch.search("crash").first().key)
        assertTrue(SettingsSearch.search("android auto").any { it.key == "private_directory" })
        assertTrue(SettingsSearch.search("my card").any { it.key == "my_details" })
    }
}
