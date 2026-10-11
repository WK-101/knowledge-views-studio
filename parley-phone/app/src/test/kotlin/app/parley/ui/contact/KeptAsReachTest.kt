package app.parley.ui.contact

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Making a contact private is never buried: "Kept as" leads "Settings for this contact" (above the calling rows),
 * and the ⋮ has Make private as a shortcut, so it is two taps from the page even with that section folded or hidden.
 * The menu's side is held by MenusTest; this scans the section's order.
 */
class KeptAsReachTest {
    private val section = File("src/main/kotlin/app/parley/ui/contact/ContactSettingsSection.kt").readText()

    @Test fun kept_as_is_the_first_row_of_the_contacts_settings() {
        val body = section.substringAfter("fun ContactSettingsSection(").substringBefore("\n}\n")
        val kept = body.indexOf("keptAsRow(ctx)")
        assertTrue("Kept as isn't in the section", kept >= 0)
        listOf("callingRows(ctx)", "savedInRows(ctx)", "variantRows(ctx)").forEach { rows ->
            assertTrue("$rows comes before Kept as", body.indexOf(rows) > kept)
        }
    }

    @Test fun the_menu_shortcut_opens_the_same_question_as_kept_as() {
        val menu = File("src/main/kotlin/app/parley/ui/contact/ContactMenuActions.kt").readText()
        assertTrue(menu.contains("ContactMenu.Action.MAKE_PRIVATE -> ctx.show(ContactDialog.ConfirmMakePrivate)"))
        assertTrue(menu.contains("canMakePrivate = !ctx.isPrivate"))
    }
}
