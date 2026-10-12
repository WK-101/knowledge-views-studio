package app.parley.ui.contact

import app.parley.common.people.EditorForm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The contact page shows labels and dates only when a contact has them: no "Add to label", "Add birthday?" or "Add
 * anniversary?" on the page (device, private and My card alike), and a section with nothing in it isn't drawn. They
 * are added in the editor, which still offers both.
 */
class ContactPageNoAddsTest {
    private val root = File(File("..").canonicalFile, "app/src/main/kotlin/app/parley/ui")
    private val page = listOf(
        "contact/ContactHeader.kt", "contact/ContactLabelChips.kt", "contact/ContactAboutSections.kt", "contact/ContactInfoSections.kt",
        "contact/ContactDetailScreen.kt", "contact/ContactPageSections.kt", "people/MeCardScreen.kt",
    ).associateWith { File(root, it).readText() }

    @Test fun the_page_offers_no_label_or_date_to_add() {
        page.forEach { (file, text) ->
            listOf("sel_add_to_label", "ux_add_birthday", "ux_add_anniversary", "MissingDateChips", "AddToLabelDialog", "labelChoices").forEach {
                assertFalse("$file still offers $it on the page", text.contains(it))
            }
        }
        assertFalse(File(root, "contact/MissingDates.kt").exists())
    }

    @Test fun empty_labels_and_dates_draw_nothing() {
        val chips = page.getValue("contact/ContactLabelChips.kt")
        assertTrue(chips.contains("if (labels.isEmpty()) return"))
        val dates = page.getValue("contact/ContactAboutSections.kt")
        val section = dates.substring(dates.indexOf("private fun DatesSection("))
        assertTrue(section.substring(0, section.indexOf("sections.addRows")).contains("if (d.events.isEmpty()) return"))
    }

    @Test fun the_editor_still_adds_labels_and_dates() {
        val device = EditorForm.allowedKinds(hasLabels = true, hasCallPicture = true, isPrivate = false)
        val private = EditorForm.allowedKinds(hasLabels = true, hasCallPicture = true, isPrivate = true)
        listOf(device, private).forEach { kinds ->
            assertTrue(EditorForm.Kind.DATE in kinds)
            assertTrue(EditorForm.Kind.LABELS in kinds)
        }
        assertTrue(EditorForm.Kind.DATE in EditorForm.meCardKinds)
    }
}
