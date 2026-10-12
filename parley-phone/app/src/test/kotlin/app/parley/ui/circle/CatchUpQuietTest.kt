package app.parley.ui.circle

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A next catch-up set after a call keeps a Circle member quiet until that day everywhere the rhythm says "due": the
 * Circle list (and the glance and the favourites' Due dot that read it), the contact page's Stay in touch card and
 * "your turn", the widget, Next up and the reminders. A scan of the sources, since each place builds its own
 * [app.parley.common.circle.CirclePlanner.Member] and a plain `snoozedUntil` there would compile and look right.
 */
class CatchUpQuietTest {
    private val root = File("src/main/kotlin/app/parley")

    @Test fun every_place_that_plans_the_circle_honours_the_catch_up() {
        listOf(
            "ui/circle/CircleUi.kt", "ui/circle/KeepInTouchLines.kt", "ui/circle/CircleContact.kt", "shortcuts/CircleDrawing.kt",
            "ui/calls/NextUpModel.kt", "work/RemindersWorker.kt",
        ).forEach { path ->
            val text = File(root, path).readText()
            val members = Regex("""CirclePlanner\.Member\(([^\n]*)""").findAll(text).map { it.groupValues[1] }.toList()
            assertTrue("$path plans no Circle member: moved?", members.isNotEmpty())
            members.forEach { args ->
                assertTrue("$path: a Circle member planned without the catch-up date: $args", !args.trimEnd().endsWith("snoozedUntil)"))
            }
            assertTrue("$path doesn't use CatchUp.quietUntil", text.contains("CatchUp.quietUntil("))
        }
    }

    @Test fun the_contact_page_passes_the_catch_up_date_on() {
        val about = File(root, "ui/contact/ContactAboutSections.kt").readText()
        assertTrue(about.contains("catchUpAt = catchUpAt"))
        val screen = File(root, "ui/contact/ContactDetailScreen.kt").readText()
        assertTrue(screen.contains("page.catchUpAt.collectAsStateWithLifecycle()"))
    }
}
