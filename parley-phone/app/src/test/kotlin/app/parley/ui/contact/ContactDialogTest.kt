package app.parley.ui.contact

import android.os.Bundle
import androidx.compose.runtime.saveable.SaverScope
import app.parley.common.ContactSummary
import app.parley.common.people.HandleLink
import app.parley.common.ux.MenuGroup
import app.parley.ui.menus.ReasonTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The contact page's one dialog state survives a rotation (a saved instance state), except what can't be kept. */
@RunWith(RobolectricTestRunner::class)
class ContactDialogTest {
    private val scope = SaverScope { v -> canBundle(v) }

    /** What Compose's saved state accepts here: values a Bundle can hold. */
    private fun canBundle(v: Any): Boolean = when (v) {
        is String, is Long, is Boolean -> true
        is ArrayList<*> -> v.all { it != null && canBundle(it) }
        else -> false
    }

    private fun roundTrip(d: ContactDialog): ContactDialog {
        val saved = with(ContactDialog.Saver) { scope.save(d) }!!
        assertTrue("$d saves as plain values", canBundle(saved))
        // Through a real Bundle, as the activity keeps it.
        val bundle = Bundle().apply { putSerializable("d", saved as java.io.Serializable) }

        @Suppress("DEPRECATION")
        val back = bundle.getSerializable("d")!!
        return ContactDialog.Saver.restore(back)!!
    }

    @Test fun every_plain_dialog_comes_back_after_a_rotation() {
        val kept = listOf(
            ContactDialog.None, ContactDialog.Menu, ContactDialog.MenuSheet(MenuGroup.PRIVACY), ContactDialog.ConfirmDelete,
            ContactDialog.DeleteWithoutCopy, ContactDialog.Qr, ContactDialog.PrivateQrWarning, ContactDialog.SecureQr, ContactDialog.Photo,
            ContactDialog.Expiry, ContactDialog.AddToHomeScreen, ContactDialog.Rhythm, ContactDialog.CopyToSim, ContactDialog.EditNote,
            ContactDialog.LogInteraction, ContactDialog.EditInteraction(42L), ContactDialog.RemindToCall, ContactDialog.ConfirmMakePrivate,
            ContactDialog.ConfirmMakeVisible, ContactDialog.SimFor("+44 20 7946 0000"), ContactDialog.MessageOn(""),
            ContactDialog.MessageOn("+1 202 555 0100"), ContactDialog.WebLink(HandleLink("https://example.org/@ana", listOf("org.example"), isWeb = true)),
            ContactDialog.Peek("+44 20 7946 0000"), ContactDialog.CallReason(ReasonTarget("+44 20 7946 0000", "Ada", "sim-1")),
            ContactDialog.CallReason(ReasonTarget("+44 20 7946 0000", null)),
        )
        kept.forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test fun the_namesakes_to_choose_from_close_instead() {
        val sam = ContactSummary(id = 1, lookupKey = "a", displayName = "Sam", photoUri = null, starred = false, phones = emptyList())
        val choose = ContactDialog.ChooseRelation(listOf(sam, sam.copy(id = 2, lookupKey = "b")))
        assertEquals(ContactDialog.None, roundTrip(choose))
    }

    @Test fun an_unknown_saved_value_opens_nothing() {
        assertEquals(ContactDialog.None, ContactDialog.Saver.restore(arrayListOf<Any>("Gone")))
        assertEquals(ContactDialog.None, ContactDialog.Saver.restore("not a list"))
    }
}
