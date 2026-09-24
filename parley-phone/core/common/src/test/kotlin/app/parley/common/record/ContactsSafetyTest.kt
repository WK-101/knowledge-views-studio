package app.parley.common.record

import app.parley.common.people.Batches
import app.parley.common.people.ContactText
import app.parley.common.people.MetaRekey
import app.parley.common.people.RelationLinks
import app.parley.common.people.TemporaryExpiry
import app.parley.common.vcard.ContactCsv
import app.parley.common.vcard.ImportReportBuilder
import app.parley.common.vcard.ParsedCard
import app.parley.common.vcard.VCardMapper
import ezvcard.Ezvcard
import ezvcard.VCardVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the round-4 contacts data-layer fixes (F1–F4, F8–F12, F16, F17, F23–F26). */
class ContactsSafetyTest {
    private fun row(mime: String, vararg v: Pair<String, String?>, primary: Boolean = false, sup: Boolean = false) =
        DataRow(mime, v.toMap(), isPrimary = primary, isSuperPrimary = sup)

    private fun group(id: Long) = row(Mime.GROUP, Col.D1 to id.toString())

    // ------------------------------------------------------------ F1 system groups survive folder sync

    @Test fun f1_system_group_memberships_are_never_deleted() {
        val myContacts = 1L; val starred = 2L; val family = 3L; val friendsLabel = 10L; val oldLabel = 11L
        val current = listOf(
            ContentDiff.Existing(100, group(myContacts)),
            ContentDiff.Existing(101, group(starred)),
            ContentDiff.Existing(102, group(family)),
            ContentDiff.Existing(103, group(friendsLabel)),
            ContentDiff.Existing(104, group(oldLabel)),
            ContentDiff.Existing(105, group(999)), // a group we can't name: untouched
        )
        // The vCard only carries user labels by title; "Friends" resolved to 10, a new label to 12.
        val desired = listOf(group(friendsLabel), group(12))
        val plan = ContentDiff.plan(current, desired, userGroupIds = setOf(friendsLabel, oldLabel, 12))
        assertEquals(listOf(104L), plan.deletes)
        assertEquals(listOf(group(12)), plan.inserts)
    }

    @Test fun f1_desired_membership_in_a_protected_group_is_not_inserted() {
        val plan = ContentDiff.plan(emptyList(), listOf(group(1)), userGroupIds = setOf(5))
        assertTrue(plan.isEmpty)
    }

    @Test fun f1_user_group_classification() {
        assertTrue(ContentDiff.isUserGroup("Book club", null, autoAdd = false, readOnly = false, favorites = false))
        assertFalse(ContentDiff.isUserGroup("My Contacts", "Contacts", autoAdd = true, readOnly = true, favorites = false))
        assertFalse(ContentDiff.isUserGroup("Starred in Android", null, autoAdd = false, readOnly = false, favorites = true))
        assertFalse(ContentDiff.isUserGroup("Coworkers", null, autoAdd = false, readOnly = true, favorites = false))
        assertFalse(ContentDiff.isUserGroup(" ", null, autoAdd = false, readOnly = false, favorites = false))
    }

    // ------------------------------------------------------------ F9 canonical diff: unchanged rows stay

    @Test fun f9_provider_rows_match_their_vcard_round_trip() {
        // As the provider stores them: normalised number in DATA4, name styles, label-less custom type…
        val provider = listOf(
            ContentDiff.Existing(1, row(Mime.NAME, Col.D1 to "Ann Lee", Col.D2 to "Ann", Col.D3 to "Lee", Col.D10 to "1", Col.D11 to "0")),
            ContentDiff.Existing(2, row(Mime.PHONE, Col.D1 to "+1 555-0100", Col.D2 to "2", Col.D4 to "+15550100", primary = true, sup = true)),
            ContentDiff.Existing(3, row(Mime.EMAIL, Col.D1 to "ann@example.com", Col.D2 to "0")),
            ContentDiff.Existing(4, row(Mime.EVENT, Col.D1 to "19800517", Col.D2 to "3")),
            ContentDiff.Existing(5, row(Mime.ORG, Col.D1 to "ACME", Col.D10 to "1")),
        )
        val record = ContactRecord("k", "Ann Lee", raws = listOf(RawRecord("com.google", "a@x", rows = provider.map { it.row })))
        val vcf = Ezvcard.write(VCardMapper.toVCard(record)).version(VCardVersion.V4_0).go()
        val remote = VCardMapper.fromVCard(Ezvcard.parse(vcf).first())
        val plan = ContentDiff.plan(provider, remote.raws.flatMap { it.rows }, emptySet())
        assertTrue("nothing should be rewritten: $plan", plan.isEmpty)
        assertEquals(5, plan.unchanged)
    }

    @Test fun f9_only_the_changed_row_is_replaced() {
        val current = listOf(
            ContentDiff.Existing(1, row(Mime.PHONE, Col.D1 to "+1 555-0100", Col.D2 to "2", Col.D4 to "+15550100")),
            ContentDiff.Existing(2, row(Mime.PHONE, Col.D1 to "+1 555-0101", Col.D2 to "3", Col.D4 to "+15550101")),
        )
        val desired = listOf(row(Mime.PHONE, Col.D1 to "+1 555-0100", Col.D2 to "2"), row(Mime.PHONE, Col.D1 to "+1 555-0199", Col.D2 to "3"))
        val plan = ContentDiff.plan(current, desired, emptySet())
        assertEquals(listOf(2L), plan.deletes)
        assertEquals(1, plan.inserts.size)
        assertEquals("+1 555-0199", plan.inserts.single()[Col.D1])
    }

    // ------------------------------------------------------------ F12 read-only rows

    @Test fun f12_read_only_rows_are_never_deleted() {
        val current = listOf(
            ContentDiff.Existing(1, row(Mime.PHONE, Col.D1 to "555 0100", Col.D2 to "2"), readOnly = true),
            ContentDiff.Existing(2, row(Mime.NOTE, Col.D1 to "old"), readOnly = false),
        )
        val plan = ContentDiff.plan(current, emptyList(), emptySet())
        assertEquals(listOf(2L), plan.deletes)
    }

    @Test fun f12_a_writable_twin_is_consumed_before_the_read_only_row() {
        val current = listOf(
            ContentDiff.Existing(1, row(Mime.PHONE, Col.D1 to "555 0100", Col.D2 to "2"), readOnly = true),
            ContentDiff.Existing(2, row(Mime.PHONE, Col.D1 to "555 0100", Col.D2 to "2")),
        )
        val plan = ContentDiff.plan(current, listOf(row(Mime.PHONE, Col.D1 to "555 0100", Col.D2 to "2")), emptySet())
        assertTrue(plan.isEmpty) // row 2 kept as the match, read-only row 1 left alone
    }

    // ------------------------------------------------------------ F3 / F10 accounts

    @Test fun f10_oem_phone_accounts_are_local() {
        assertTrue(AccountKinds.isLocalType(null))
        assertTrue(AccountKinds.isLocalType("vnd.sec.contact.phone"))
        assertTrue(AccountKinds.isLocalType("com.android.contacts.default"))
        assertFalse(AccountKinds.isLocalType("com.google"))
        assertTrue(AccountKinds.isWritable("vnd.sec.contact.phone", uploadingTypes = emptySet()))
    }

    @Test fun f3_sim_and_messenger_accounts_are_never_targets() {
        val uploading = setOf("com.google", "vnd.sec.contact.sim", "com.whatsapp", "at.bitfire.davdroid.address_book")
        assertFalse(AccountKinds.isWritable("vnd.sec.contact.sim", uploading))
        assertFalse(AccountKinds.isWritable("com.android.contacts.usim", uploading))
        assertFalse(AccountKinds.isWritable("com.whatsapp", uploading))
        assertFalse(AccountKinds.isWritable("org.telegram.messenger", uploading))
        assertFalse(AccountKinds.isWritable("com.example.readonly", uploading))
        assertTrue(AccountKinds.isWritable("com.google", uploading))
        assertTrue(AccountKinds.isWritable("at.bitfire.davdroid.address_book", uploading))
        assertTrue(AccountKinds.isWritable("com.android.local", emptySet(), platformLocalType = "com.android.local"))
        val targets = AccountKinds.writableTargets(
            listOf("com.google", "vnd.sec.contact.sim", "com.whatsapp", "com.readonly"), { it }, uploading, device = "device",
        )
        assertEquals(listOf("device", "com.google"), targets)
    }

    // ------------------------------------------------------------ F26 primary flags

    @Test fun f26_one_primary_per_kind_per_raw_and_one_super_primary_per_contact() {
        val a = listOf(
            row(Mime.PHONE, Col.D1 to "1", primary = true, sup = true),
            row(Mime.PHONE, Col.D1 to "2", primary = true),
            row(Mime.EMAIL, Col.D1 to "a@x", primary = true),
        )
        val b = listOf(row(Mime.PHONE, Col.D1 to "3", primary = true, sup = true), row(Mime.PHONE, Col.D1 to "4", primary = true))
        val (na, nb) = PrimaryFlags.normalize(listOf(a, b))
        assertEquals(listOf(true, false, true), na.map { it.isPrimary })
        assertEquals(listOf(true, false, false), na.map { it.isSuperPrimary })
        assertEquals(listOf(true, false), nb.map { it.isPrimary })
        assertEquals(listOf(false, false), nb.map { it.isSuperPrimary })
        val merged = PrimaryFlags.normalizeOne(a + b)
        assertEquals(1, merged.count { it.mimeType == Mime.PHONE && it.isPrimary })
        assertEquals(1, merged.count { it.isSuperPrimary })
    }

    // ------------------------------------------------------------ F2 temporary contacts

    @Test fun f2_only_the_created_raws_are_deleted_and_history_kept_when_merged() {
        val d = TemporaryExpiry.decide(stored = setOf(10), current = setOf(10, 20, 21), purgeRequested = true)
        assertEquals(setOf(10L), d.deleteRaws)
        assertFalse(d.purgeHistory)
        assertTrue(d.keptMerged)
    }

    @Test fun f2_an_untouched_temporary_contact_goes_with_its_history() {
        val d = TemporaryExpiry.decide(setOf(10), setOf(10), purgeRequested = true)
        assertEquals(setOf(10L), d.deleteRaws)
        assertTrue(d.purgeHistory)
        assertFalse(d.keptMerged)
    }

    @Test fun f2_old_entries_never_delete_a_merged_contact() {
        assertTrue(TemporaryExpiry.decide(null, setOf(1, 2), true).let { it.nothingToDo && it.keptMerged })
        assertEquals(setOf(1L), TemporaryExpiry.decide(null, setOf(1), true).deleteRaws)
        assertTrue(TemporaryExpiry.decide(setOf(5), emptySet(), true).nothingToDo)
        // The recorded raw is gone (moved or deleted): nothing of anyone else is touched.
        assertTrue(TemporaryExpiry.decide(setOf(5), setOf(6), true).nothingToDo)
    }

    @Test fun f2_joining_with_a_real_contact_clears_the_flag() {
        assertNull(TemporaryExpiry.afterJoin(listOf(setOf(1L) to 100L), joinedContacts = 2))
        assertEquals(setOf(1L, 2L) to 50L, TemporaryExpiry.afterJoin(listOf(setOf(1L) to 100L, setOf(2L) to 50L), joinedContacts = 2))
        assertEquals(setOf(3L, 4L), TemporaryExpiry.decodeIds(TemporaryExpiry.encodeIds(listOf(4, 3, 3))))
        assertNull(TemporaryExpiry.decodeIds(null))
    }

    // ------------------------------------------------------------ F8 re-keying metadata

    @Test fun f8_changed_keys_move_and_unresolved_keys_stay() {
        val moves = MetaRekey.plan(mapOf("a" to "a", "b" to "c", "d" to null, "e" to "c"))
        assertEquals(listOf(MetaRekey.Move("b", "c"), MetaRekey.Move("e", "c")), moves)
    }

    @Test fun f8_a_sweep_never_hands_metadata_to_a_namesake() {
        // Same contact, key changed (first Google sync): follow it.
        assertTrue(MetaRekey.plausible("0r5-4E4E", "1234i5", storedId = 7, resolvedId = 7))
        // Linked by another app: the new key contains the old raw's segment.
        assertTrue(MetaRekey.plausible("0r5-4E4E", "0r5-4E4E.0r9-5A5A", storedId = 7, resolvedId = 12))
        // A different contact that merely has the same name: stay put.
        assertFalse(MetaRekey.plausible("0r5-4E4E", "0r9-4E4E", storedId = 7, resolvedId = 12))
        assertFalse(MetaRekey.plausible("0r5-4E4E", "0r9-4E4E", storedId = null, resolvedId = 12))
    }

    @Test fun f8_merging_two_rows_keeps_what_the_user_wrote() {
        val into = MetaRekey.Values(pinnedNote = "Gate code 1234", preferredMessenger = "org.thoughtcrime.securesms", reachOutDays = 30)
        val from = MetaRekey.Values(pinnedNote = "Allergic to nuts", preferredMessenger = "com.whatsapp", reachOutDays = 14, lastNudgedAt = 5)
        val m = MetaRekey.merge(into, from)
        assertEquals("Gate code 1234\nAllergic to nuts", m.pinnedNote)
        assertEquals("org.thoughtcrime.securesms", m.preferredMessenger)
        assertEquals(14, m.reachOutDays)
        assertEquals(5L, m.lastNudgedAt)
        assertEquals(from, MetaRekey.merge(null, from))
        assertEquals("same", MetaRekey.merge(MetaRekey.Values(pinnedNote = "same"), MetaRekey.Values(pinnedNote = " same ")).pinnedNote)
    }

    // ------------------------------------------------------------ F23 relations by lookup key

    @Test fun f23_relation_opens_the_stored_contact_then_falls_back_to_a_unique_name() {
        val people = listOf(1L to "Anna", 2L to "Anna", 3L to "Ben")
        val link = RelationLinks.Link("key-anna-2", 2)
        assertEquals(RelationLinks.Target.Contact(7), RelationLinks.resolve("Anna", link, { 7L }, people))
        assertEquals(RelationLinks.Target.Choose(listOf(1L, 2L)), RelationLinks.resolve("anna ", link, { null }, people))
        assertEquals(RelationLinks.Target.Contact(3), RelationLinks.resolve("Ben", null, { null }, people))
        assertEquals(RelationLinks.Target.None, RelationLinks.resolve("Zoe", null, { null }, people))
        assertEquals(RelationLinks.Target.None, RelationLinks.resolve("Ben", null, { null }, people, self = 3))
    }

    @Test fun f23_links_are_updated_and_round_trip() {
        val contacts = listOf(Triple(1L, "Anna", "ka1"), Triple(2L, "Anna", "ka2"), Triple(3L, "Ben\tB", "kb"))
        val existing = mapOf("anna" to RelationLinks.Link("ka2", 2))
        val links = RelationLinks.update(listOf("Anna", "Ben\tB", "Nobody", "Old"), existing, contacts)
        assertEquals(mapOf("anna" to RelationLinks.Link("ka2", 2), "ben\tb" to RelationLinks.Link("kb", 3)), links)
        assertEquals(links, RelationLinks.decode(RelationLinks.encode(links)))
        // Ambiguous name without a stored link: not linked (the page asks instead).
        assertTrue(RelationLinks.update(listOf("Anna"), emptyMap(), contacts).isEmpty())
    }

    // ------------------------------------------------------------ F24 / F25 text

    @Test fun f24_blank_contacts_get_a_name() {
        assertEquals("1 Main St", ContactText.blankContactName(listOf(Mime.NOTE to "Note", Mime.POSTAL to "1 Main St\nSpringfield")))
        assertEquals("Call after 6", ContactText.blankContactName(listOf(Mime.NOTE to "Call after 6")))
        assertEquals(ContactText.NO_NAME, ContactText.blankContactName(emptyList()))
    }

    @Test fun f25_formatted_address_keeps_po_box_and_neighbourhood() {
        assertEquals(
            "1 Main St, PO Box 12, Old Town, 62701 Springfield, IL, USA",
            ContactText.postal("1 Main St", "12", "Old Town", "62701", "Springfield", "IL", "USA"),
        )
        assertEquals("Postfach 3, Berlin", ContactText.postal("", "Postfach 3", "", "", "Berlin"))
    }

    // ------------------------------------------------------------ F16 batches

    @Test fun f16_linking_many_copies_stays_under_the_batch_limit() {
        val pairs = Batches.pairs((1L..40L).toList())
        assertEquals(780, pairs.size)
        val chunks = Batches.chunks(pairs)
        assertTrue(chunks.all { it.size <= Batches.MAX_OPS })
        assertEquals(pairs, chunks.flatten())
        assertTrue(Batches.chunks(emptyList<Int>()).isEmpty())
    }

    // ------------------------------------------------------------ F17 CSV separators and number lists

    private fun readCsv(text: String): Pair<List<ContactRecord>, app.parley.common.vcard.ImportReport> {
        val report = ImportReportBuilder()
        val out = ArrayList<ContactRecord>()
        ContactCsv.read(text.reader(), report) { c: ParsedCard -> out += c.record }
        return out to report.build()
    }

    @Test fun f17_semicolon_and_tab_separated_files_import() {
        for (sep in listOf(";", "\t")) {
            val (list, report) = readCsv("Given${sep}Family${sep}Phone 1 Type${sep}Phone 1 Value\r\nAnn${sep}Lee${sep}Mobile${sep}+1 555 0100\r\n")
            assertEquals("separator '$sep'", 1, list.size)
            assertTrue(report.failures.isEmpty())
            val rows = list.single().raws.single().rows
            assertEquals("+1 555 0100", rows.single { it.mimeType == Mime.PHONE }[Col.D1])
            assertEquals("Ann", rows.single { it.mimeType == Mime.NAME }[Col.D2])
        }
        assertEquals(';', ContactCsv.detectDelimiter("Given;Family;\"a,b\""))
        assertEquals(',', ContactCsv.detectDelimiter("Given"))
    }

    @Test fun f17_single_column_number_lists_become_contacts() {
        val (list, report) = readCsv("﻿Phone\n+44 20 7946 0000\n0300 123 4567\n\n")
        assertEquals(2, list.size)
        assertEquals(listOf("+44 20 7946 0000", "0300 123 4567"), list.map { it.raws.single().rows.single { r -> r.mimeType == Mime.PHONE }[Col.D1] })
        assertEquals("+44 20 7946 0000", list.first().displayName)
        assertTrue(report.failures.isEmpty())
        assertTrue(ContactCsv.looksLikeNumberList(listOf("+1 555 0100", "555-0101")))
        assertFalse(ContactCsv.looksLikeNumberList(listOf("Given,Family", "Ann,Lee")))
        assertFalse(ContactCsv.looksLikeNumberList(listOf("Hello", "World")))
    }
}
