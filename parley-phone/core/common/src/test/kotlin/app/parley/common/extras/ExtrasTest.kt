package app.parley.common.extras

import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry
import app.parley.common.SettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ExtrasTest {
    // X2: trip mode

    private val ana = TripMatch.Person(1, "Ana", places = listOf("Lisboa", "Portugal"))
    private val marco = TripMatch.Person(2, "Marco", note = "Moved to São Paulo last spring")
    private val jo = TripMatch.Person(3, "Jo", numberPlaces = listOf("Lisbon"))
    private val romeo = TripMatch.Person(4, "Romeo", places = listOf("Romeoville, IL"))

    @Test fun trip_match_ignores_case_and_accents() {
        assertEquals(listOf("Marco"), TripMatch.match("sao paulo", listOf(ana, marco, jo)).map { it.person.name })
        assertEquals(listOf("Marco"), TripMatch.match("SÃO PAULO", listOf(ana, marco, jo)).map { it.person.name })
        assertEquals(listOf("Ana"), TripMatch.match("lisboa", listOf(ana, marco, jo)).map { it.person.name })
    }

    @Test fun trip_match_uses_whole_words_only() {
        assertTrue(TripMatch.match("Rome", listOf(romeo)).isEmpty())
        assertTrue(TripMatch.mentions("Rome, Italy", "rome"))
        assertFalse(TripMatch.mentions("anything", "  "))
    }

    @Test fun trip_match_orders_address_before_number_before_note() {
        val noted = TripMatch.Person(5, "Aaron", note = "lives in Lisbon")
        val addressed = TripMatch.Person(6, "Zoe", places = listOf("Lisbon"))
        val hits = TripMatch.match("Lisbon", listOf(noted, jo, addressed))
        assertEquals(listOf("Zoe", "Jo", "Aaron"), hits.map { it.person.name })
        assertEquals(TripMatch.Reason.NUMBER, hits[1].best)
    }

    @Test fun city_choices_merge_spellings_and_put_recent_first() {
        val choices = TripMatch.cityChoices(listOf("Lisbon", "lisbon", "Lisbon", "Porto", "  ", "Berlin", "Porto"), recent = "Paris")
        assertEquals(listOf("Paris", "Lisbon", "Porto", "Berlin"), choices)
        assertEquals(listOf("LISBON", "Porto"), TripMatch.cityChoices(listOf("Porto", "Lisbon", "Lisbon"), recent = "LISBON"))
    }

    // X3: label policies

    @Test fun label_sim_follows_alphabetical_labels_and_available_sims() {
        val p = mapOf("Work" to LabelPolicy(simId = "sim2"), "Family" to LabelPolicy(simId = "sim1"))
        assertEquals("sim1", LabelPolicies.simFor(setOf("Work", "Family"), p))
        assertEquals("sim2", LabelPolicies.simFor(setOf("Work", "Family"), p, available = setOf("sim2")))
        assertNull(LabelPolicies.simFor(setOf("Friends"), p))
    }

    @Test fun label_rhythm_is_the_shortest_gap() {
        val p = mapOf("Family" to LabelPolicy(rhythmDays = 14), "Friends" to LabelPolicy(rhythmDays = 30))
        assertEquals("Family" to 14, LabelPolicies.rhythmFor(setOf("Friends", "Family"), p))
        assertNull(LabelPolicies.rhythmFor(setOf("Work"), p))
    }

    @Test fun label_policies_round_trip_and_follow_renames() {
        val p = mapOf("Family" to LabelPolicy(simId = "a", allowThroughDnd = true, starredByPolicy = setOf("k1")), "Empty" to LabelPolicy())
        val decoded = LabelPolicies.decode(LabelPolicies.encode(p))
        assertEquals(setOf("Family"), decoded.keys)
        assertEquals(p["Family"], decoded["Family"])
        assertEquals(setOf("Kin"), LabelPolicies.renamed(decoded, mapOf("Family" to "Kin")).keys)
        // A merge into a label that has its own policy keeps the target's.
        val merged = LabelPolicies.renamed(mapOf("A" to LabelPolicy(simId = "1"), "B" to LabelPolicy(simId = "2")), mapOf("A" to "B"))
        assertEquals(mapOf("B" to LabelPolicy(simId = "2")), merged)
        assertTrue(LabelPolicies.deleted(decoded, setOf("Family")).isEmpty())
        assertTrue(LabelPolicies.decode("not json").isEmpty())
    }

    // X4: simple mode

    private fun contact(id: Long, name: String, key: String, vararg numbers: String) =
        ContactSummary(id, key, name, null, false, numbers.map { PhoneEntry(it, 2, null) })

    @Test fun simple_setup_exports_without_keys_and_never_on() {
        val c = SimpleConfig(enabled = true, people = listOf(SimplePerson("Ana", "+351 912 345 678", "k1")), speakName = true)
        val back = SimpleSetup.import(SimpleSetup.export(c))
        assertFalse(back.enabled)
        assertTrue(back.speakName)
        assertEquals(listOf(SimplePerson("Ana", "+351 912 345 678", null)), back.people)
    }

    @Test(expected = IllegalArgumentException::class)
    fun simple_setup_rejects_other_text() {
        SimpleSetup.import("{\"hello\":1}")
    }

    @Test fun simple_setup_keeps_nine_distinct_people() {
        val people = (1..12).map { SimplePerson("P$it", "+3519123456%02d".format(java.util.Locale.ROOT, it)) } + SimplePerson("Dup", "+351 912 345 601")
        val c = with(SimpleSetup) { SimpleConfig(people = people).normalised() }
        assertEquals(9, c.people.size)
        assertEquals("P1", c.people.first().name)
    }

    @Test fun simple_setup_resolves_by_key_then_number_then_name() {
        val contacts = listOf(contact(1, "Ana Silva", "k1", "912345678"), contact(2, "Marco", "k2", "+49 170 1234567"), contact(3, "Jo", "k3"))
        val r = SimpleSetup.resolve(
            listOf(SimplePerson("Mum", "0000", "k1"), SimplePerson("M.", "0170 1234567"), SimplePerson("jo", "555"), SimplePerson("Nobody", "123")),
            contacts,
        )
        assertEquals(listOf(1L, 2L, 3L, null), r.map { it.contact?.id })
    }

    @Test fun simple_grid_sizes() {
        assertEquals(1 to 1, SimpleSetup.grid(1))
        assertEquals(2 to 2, SimpleSetup.grid(4))
        assertEquals(2 to 3, SimpleSetup.grid(5))
        assertEquals(3 to 3, SimpleSetup.grid(9))
    }

    // X5: handshake

    @Test fun handshake_note_appends_once() {
        assertEquals("Met at Café on 25 Sep", Handshake.appendToNote("", "Met at Café on 25 Sep"))
        assertEquals("Likes jazz\nMet at X", Handshake.appendToNote("Likes jazz\n", "Met at X"))
        assertEquals("Likes jazz\nMet at X", Handshake.appendToNote("Likes jazz\nMet at X", "Met at X"))
        assertEquals("a b", Handshake.cleanPlace("  a \n b "))
        assertTrue(Handshake.meetKey("n").startsWith("m:"))
    }

    // C5: Markdown notes

    @Test fun markdown_has_front_matter_and_timeline() {
        val t = LocalDate.of(2026, 9, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val md = MarkdownNotes.render(
            MarkdownNotes.Person(
                name = "Ana \"Nana\" Silva",
                phones = listOf(MarkdownNotes.Field("Mobile", "+351 912 345 678")),
                emails = listOf(MarkdownNotes.Field("", "ana@example.org")),
                dates = listOf(MarkdownNotes.Field("Birthday", "1990-05-02")),
                labels = listOf("Friends", "Family", "Friends"),
                pinnedNote = "Ask about: the move",
                keepInTouch = "Every 30 days", keepInTouchDays = 30,
                timeline = listOf(MarkdownNotes.Entry(t, "Met", "Coffee\nat the bay"), MarkdownNotes.Entry(t + 86_400_000L, "Call · 4 min")),
            ),
            ZoneOffset.UTC, t,
        )
        assertTrue(md.startsWith("---\nname: \"Ana \\\"Nana\\\" Silva\"\n"))
        assertTrue(md.contains("phones:\n  - \"+351 912 345 678 (Mobile)\"\n"))
        assertTrue(md.contains("emails:\n  - \"ana@example.org\"\n"))
        assertTrue(md.contains("dates:\n  - \"Birthday: 1990-05-02\"\n"))
        assertTrue(md.contains("labels:\n  - \"Family\"\n  - \"Friends\"\nkeep_in_touch_days: 30\nexported: 2026-09-20\n---\n"))
        assertTrue(md.contains("## Pinned note\n\nAsk about: the move\n"))
        // Newest first; notes on one line.
        assertTrue(md.contains("- 2026-09-21 00:00 · Call · 4 min\n- 2026-09-20 00:00 · Met · Coffee at the bay\n"))
        assertFalse(md.contains("## Note\n"))
    }

    @Test fun markdown_file_names_are_safe_and_unique() {
        val taken = HashSet<String>()
        assertEquals("Ana Marco.md", MarkdownNotes.fileName("Ana / Marco?", taken))
        assertEquals("ana marco (2).md", MarkdownNotes.fileName("ana marco", taken))
        assertEquals("Contact.md", MarkdownNotes.fileName("...", taken))
        assertEquals("x.md", MarkdownNotes.fileName(" .x. ", taken))
        assertEquals(83, MarkdownNotes.fileName("a".repeat(200), taken).length)
        assertEquals("\"a\\nb\"", MarkdownNotes.yaml("a\nb"))
    }

    @Test fun extras_settings_are_in_the_catalog() {
        listOf("simple_mode", "markdown_export").forEach { SettingsCatalog[it] }
    }
}
