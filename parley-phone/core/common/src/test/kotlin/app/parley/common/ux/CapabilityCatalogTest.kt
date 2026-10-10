package app.parley.common.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CapabilityCatalogTest {
    private val rows = CapabilityCatalog.rows

    @Test fun every_row_leads_somewhere() {
        // Settings rows must name a real setting; screens are mapped exhaustively in the app (and opened in its graph test).
        assertEquals(emptyList<String>(), CapabilityCatalog.unknownSettings())
        assertEquals("keys are unique", rows.size, rows.map { it.key }.toSet().size)
        rows.forEach { assertTrue("${it.key} has a stable id", Regex("[a-z0-9_]{1,40}").matches(it.key)) }
    }

    @Test fun every_job_has_rows_and_every_row_is_one_line() {
        // Help is one row that opens its own pages; every other job groups several.
        Job.entries.filter { it != Job.HELP }.forEach { j -> assertTrue("$j has at least three rows", CapabilityCatalog.forJob(j).size >= 3) }
        assertEquals(listOf("help"), CapabilityCatalog.forJob(Job.HELP).map { it.key })
        rows.forEach { r ->
            assertTrue("${r.key} title is short", r.title.length <= 48)
            assertTrue("${r.key} summary is one line", r.summary.length <= 90 && '\n' !in r.summary)
        }
        assertEquals(rows.size, Job.entries.sumOf { CapabilityCatalog.forJob(it).size })
    }

    @Test fun the_hub_keeps_every_former_tools_row_up_front() {
        // Tools and "What Parley can do" became one page: what Tools listed stays one tap away, before any "More".
        val formerTools = listOf(
            "birthdays", "temporary", "health", "scan_qr", "import_export", "coming_from", "who_rings", "expecting", "messaged",
            "history_undo", "backup", "privacy_dashboard", "lock_now",
        )
        formerTools.forEach { k -> assertTrue("$k is featured", rows.single { it.key == k }.featured) }
        // Every job shows something before "More", and folds at least part of a long list.
        Job.entries.forEach { j -> assertTrue("$j has featured rows", CapabilityCatalog.featured(j).isNotEmpty()) }
        Job.entries.forEach { j -> assertEquals(CapabilityCatalog.forJob(j).size, CapabilityCatalog.featured(j).size + CapabilityCatalog.more(j).size) }
        assertTrue("the hub starts shorter than the catalog", Job.entries.sumOf { CapabilityCatalog.featured(it).size } < rows.size * 2 / 3)
    }

    @Test fun actions_run_in_place_and_still_lead_somewhere() {
        assertEquals(CapabilityAction.LOCK_NOW, rows.single { it.key == "lock_now" }.action)
        assertEquals(CapabilityAction.EXPECTING_CALL, rows.single { it.key == "expecting" }.action)
        assertEquals(CapabilityTarget.Setting("app_lock"), rows.single { it.key == "lock_now" }.target)
        assertEquals(CapabilityAction.entries.toSet(), rows.mapNotNull { it.action }.toSet())
        assertEquals(4, rows.count { it.action != null })
        // The privacy dashboard lives under Settings › Privacy and stays reachable from the privacy job.
        assertEquals(Job.KEEP_PRIVATE, rows.single { it.target == CapabilityTarget.Screen(AppScreen.PRIVACY_DASHBOARD) }.job)
        assertEquals(CapabilityTarget.Setting("reminders"), rows.single { it.key == "reminders" }.target)
    }

    /** Every screen a row can open has its row: a new [AppScreen] without one fails here. */
    @Test fun every_screen_has_a_row() {
        val shown = rows.mapNotNull { (it.target as? CapabilityTarget.Screen)?.screen }.toSet()
        assertEquals(emptySet<AppScreen>(), AppScreen.entries.toSet() - shown)
    }

    /** One row per destination: two rows that open the same place are one row with a better summary. */
    @Test fun no_two_rows_open_the_same_place() {
        // A row that runs in place (Lock now, Is this a scam?…) only names a target for search; it opens nothing.
        val links = rows.filter { it.action == null }
        val twice = links.groupBy { it.target }.filterValues { it.size > 1 }.mapValues { (_, v) -> v.map { it.key } }
        assertEquals(emptyMap<CapabilityTarget, List<String>>(), twice)
    }

    /**
     * Every headline feature of a "New in" line of the README since 6.0 has its row, tagged with the release that
     * brought it, so What's new can name it. A new headline fails here until it has a row.
     */
    @Test fun every_headline_feature_has_its_row() {
        // "This number never calls you" and Dead-number radar are parts of other places (the call screen and the
        // number's history, the Health check), not places of their own.
        val headlines = mapOf(
            "Recall" to ("search_everything" to "6.0"),
            "Situations" to ("situations" to "6.0"),
            "Case files" to ("case_files" to "6.1"),
            "Family spam shield" to ("family_shield" to "6.1"),
            "Chapters" to ("chapters" to "6.2"),
            "Archive" to ("archived" to "6.2"),
            "Agenda" to ("to_talk_about" to "6.2"),
            "Rescue call" to ("rescue_call" to "6.2"),
            "Help & troubleshooting" to ("help" to "6.4"),
        )
        headlines.forEach { (feature, row) ->
            val (key, since) = row
            assertEquals(feature, since, rows.single { it.key == key }.since)
        }
        // Dead-number radar is part of the Health check, and its row says so.
        assertTrue(CapabilitySearch.search("dead number", rows).any { it.key == "health" })
        // The README names each of them in its "New in" lines (when the README is there to read).
        readme()?.let { text -> (headlines.keys - "Help & troubleshooting").forEach { assertTrue("README names $it", it in text) } }
    }

    private fun readme(): String? = listOf("../../README.md", "../README.md", "README.md").map(::File).firstOrNull { it.isFile }?.readText()

    @Test fun situations_took_the_drive_profiles_place() {
        val situations = rows.single { it.key == "situations" }
        assertTrue(situations.featured)
        assertEquals(Job.BETTER_CALLS, situations.job)
        assertEquals(CapabilityTarget.Setting("situations"), situations.target)
        assertTrue(!rows.single { it.key == "drive_profile" }.featured)
        // The features that live on each label or contact open their help page, which leads to the place.
        listOf("chapters" to HelpTopic.CHAPTERS, "to_talk_about" to HelpTopic.TALK_ABOUT, "family_shield" to HelpTopic.FAMILY_SHIELD).forEach { (k, t) ->
            assertEquals(k, CapabilityTarget.Help(t), rows.single { it.key == k }.target)
        }
    }

    @Test fun whats_new_names_the_releases_headline_rows() {
        // 6.4 gave the 6.x features their rows: its headline is what became visible, featured first.
        assertEquals(listOf("search_everything", "situations", "help"), CapabilityCatalog.headline("6.4.0").map { it.key })
        // Rows Tools didn't show in 6.0 aren't "new in 6.0".
        assertEquals(emptyList<String>(), CapabilityCatalog.headline("6.0.0").map { it.key })
        assertEquals(listOf("rescue_call"), CapabilityCatalog.headline("6.2", max = 3).map { it.key })
        assertEquals(emptyList<Capability>(), CapabilityCatalog.headline("1.0"))
    }

    /** What arrived after 4.6 has a row (PRODUCT.md §1.2): the hub lists everything Parley does. */
    @Test fun the_hub_has_caught_up() {
        listOf(
            "drive_profile", "phone_menus", "calling_abroad", "call_quality", "rtt", "shared_labels", "parley_pin", "scam_check",
            "voicemail", "speed_dial", "private_names", "introduce", "sims",
        ).forEach { k -> assertTrue(k, rows.any { it.key == k }) }
        // Plan minutes and "Introduce myself…" are hidden elsewhere and kept here, folded under "More".
        listOf("sims", "introduce").forEach { k -> assertTrue(k, !rows.single { it.key == k }.featured) }
        assertTrue(rows.none { it.key == "card_updates" })
    }

    @Test fun whos_in_has_a_way_in_besides_the_contacts_chip() {
        // The Contacts tab can be hidden: Tools and Settings search still lead to Who's in….
        val row = rows.single { it.target == CapabilityTarget.Screen(AppScreen.TRIP) }
        assertEquals(Job.STAY_IN_TOUCH, row.job)
        listOf("trip", "travel").forEach { q -> assertEquals(q, listOf(row.key), CapabilitySearch.search(q, rows).map { it.key }) }
        assertTrue(row.key in CapabilitySearch.search("who's in", rows).map { it.key })
    }

    @Test fun search_matches_word_starts_in_any_field() {
        fun keys(q: String) = CapabilitySearch.search(q, rows).map { it.key }
        assertTrue("message_number" in keys("whatsapp"))
        // Accents and case don't matter; every word has to match.
        assertEquals(listOf("coming_from"), keys("IPHONE"))
        assertTrue("history_undo" in keys("Undo"))
        assertEquals(listOf("snapshots"), keys("daily snap"))
        // The job's name finds its rows.
        assertTrue(keys("spam").containsAll(CapabilityCatalog.forJob(Job.STOP_SPAM).map { it.key }.take(2)))
        // A blank query keeps everything; nonsense finds nothing.
        assertEquals(rows.size, CapabilitySearch.search("  ", rows).size)
        assertEquals(emptyList<String>(), keys("zzqx"))
    }

    @Test fun localised_texts_are_searched_too() {
        val german: (Capability) -> Pair<String, String> = { c -> if (c.key == "insights") "Anrufe im Überblick" to "" else c.title to c.summary }
        assertEquals(listOf("insights"), CapabilitySearch.search("anrufe", rows, german).map { it.key })
        assertEquals(listOf("insights"), CapabilitySearch.search("uberblick", rows, german).map { it.key })
    }

    @Test fun new_rows_by_release() {
        assertEquals("4.6", CapabilityCatalog.majorMinor("4.6.0-debug"))
        assertEquals("4.6", CapabilityCatalog.majorMinor("4.6"))
        assertTrue(CapabilityCatalog.newIn("4.6.0").any { it.key == "coming_from" })
        assertTrue(CapabilityCatalog.newIn("4.6.0").none { it.key == "sales_lines" })
        assertEquals(emptyList<Capability>(), CapabilityCatalog.newIn("1.0"))
    }

    @Test fun coming_from_covers_each_importer() {
        val grouped = ComingFrom.grouped()
        assertEquals(ComingFrom.Importer.entries.toList(), grouped.map { it.first })
        assertEquals(ComingFrom.Source.entries.size, grouped.sumOf { it.second.size })
        // Another phone with Parley comes first: a restore brings everything, and overwrites what the first run set.
        assertEquals(ComingFrom.Importer.PARLEY_BACKUP, grouped.first().first)
        assertEquals(listOf(ComingFrom.Source.PARLEY), grouped.first().second)
        assertEquals(listOf(ComingFrom.Source.GOOGLE, ComingFrom.Source.IPHONE, ComingFrom.Source.SAMSUNG), grouped[1].second)
    }
}
