package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactPageBlocksTest {
    private val all = ContactSection.entries.toSet()
    private fun ContactPageLayout.keys(present: Set<ContactSection> = all) = blocks(present).map { it.key }

    @Test fun defaults_join_contact_info_and_about_into_one_group_each() {
        val blocks = ContactPageLayout().blocks(all)
        assertEquals(
            listOf("stay", "phones+emails+addresses+messengers+profiles", "dates+about+more+note", "timeline", "insights", "other", "settings"),
            blocks.map { it.key },
        )
        assertEquals(SectionFamily.CONTACT_INFO, blocks[1].family)
        assertEquals(SectionFamily.ABOUT, blocks[2].family)
        assertNull(blocks[0].family)
        assertFalse(blocks[0].merged)
    }

    @Test fun empty_sections_are_left_out_and_their_neighbours_still_join() {
        val present = setOf(ContactSection.PHONES, ContactSection.ADDRESSES, ContactSection.TIMELINE, ContactSection.SETTINGS)
        assertEquals(listOf("phones+addresses", "timeline", "settings"), ContactPageLayout().keys(present))
    }

    @Test fun one_section_of_a_family_is_drawn_on_its_own() {
        val present = setOf(ContactSection.PHONES, ContactSection.DATES)
        val blocks = ContactPageLayout().blocks(present)
        assertEquals(listOf("phones", "dates"), blocks.map { it.key })
        assertTrue(blocks.none { it.merged })
    }

    @Test fun a_section_moved_away_from_its_family_stays_apart() {
        val l = ContactPageLayout().let { it.moved(it.order.indexOf(ContactSection.EMAILS), it.order.indexOf(ContactSection.TIMELINE)) }
        assertEquals(
            listOf("stay", "phones+addresses+messengers+profiles", "dates+about+more+note", "timeline", "emails", "insights", "other", "settings"),
            l.keys(),
        )
    }

    @Test fun hidden_sections_are_not_drawn() {
        val l = ContactPageLayout().withMode(ContactSection.MESSENGERS, SectionMode.HIDDEN)
        assertEquals("phones+emails+addresses+profiles", l.keys()[1])
    }

    @Test fun sections_folded_differently_do_not_join_and_a_block_folds_as_one() {
        val split = ContactPageLayout().withFold(ContactSection.ADDRESSES, true)
        assertEquals(listOf("phones+emails", "addresses", "messengers+profiles"), split.keys().subList(1, 4))

        val info = ContactPageLayout().blocks(all)[1]
        val folded = ContactPageLayout().withFold(info, true)
        assertTrue(folded.isFolded(info))
        assertEquals(info.sections.toSet(), folded.folds.keys)
        assertEquals(info.key, folded.blocks(all)[1].key) // still one group while folded
        assertFalse(folded.withFold(info, false).isFolded(info))
        assertTrue(folded.withFold(info, false).folds.isEmpty())
    }

    @Test fun the_old_default_order_saved_with_a_fold_reads_as_the_new_default() {
        val old = "stay:o,dates:o,phones:o,emails:o,addresses:o,messengers:o,about:o,other:f,timeline:o,insights:f,note:o,settings:f|timeline:1"
        val l = ContactPageLayout.decode(old)
        assertEquals(ContactSection.entries, l.order)
        assertTrue(l.isFolded(ContactSection.TIMELINE))
    }

    @Test fun an_order_the_user_chose_is_kept() {
        val chosen = "timeline:o,stay:o,dates:o,phones:o,emails:o,addresses:o,messengers:o,about:o,other:f,insights:f,note:o,settings:f|"
        val l = ContactPageLayout.decode(chosen)
        assertEquals(ContactSection.TIMELINE, l.order[0])
        assertEquals(ContactSection.DATES, l.order[2])
        // Dates sits before the contact info here, so it isn't joined with About.
        assertEquals(listOf("timeline", "stay", "dates", "phones+emails+addresses+messengers+profiles", "about+more"), l.keys().take(5))
    }
}
