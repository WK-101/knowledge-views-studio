package app.parley.common.cases

import app.parley.common.cases.BookingCodes.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookingCodesTest {
    private val email = """
        Thank you for booking with Sky Air.
        Booking reference: QX7R2B
        Flight SA 214 on 12/11/2026 at 07:45
        Ticket number 125-2186449031
        Questions? Call us on +44 20 7946 0958.
        Paid with card 4111 1111 1111 1111.
    """.trimIndent()

    @Test fun finds_the_named_codes_first_ticked() {
        val found = BookingCodes.find(email, listOf("+442079460958"))
        assertEquals(Kind.BOOKING, found.first().kind)
        assertEquals("QX7R2B", found.first().value)
        assertTrue(found.first().named)
        assertTrue(found.any { it.kind == Kind.TICKET && it.value == "125-2186449031" })
    }

    @Test fun never_offers_a_card_a_phone_number_a_date_or_a_time() {
        val values = BookingCodes.find(email, listOf("+442079460958")).map { it.value }
        assertFalse(values.any { it.filter(Char::isDigit) == "4111111111111111" })
        assertFalse(values.any { "79460958" in it.filter(Char::isDigit) })
        assertFalse("12/11/2026" in values)
        assertFalse("07:45" in values)
        // A card number named as a reference is still never offered.
        assertTrue(BookingCodes.find("Reference: 4111111111111111").isEmpty())
        assertTrue(BookingCodes.find("Order number 2026-11-12").isEmpty())
    }

    @Test fun a_code_by_its_shape_comes_unticked() {
        val found = BookingCodes.find("Your seat is confirmed. Code AB12CD, see you soon.")
        assertEquals(listOf(BookingCodes.Found(null, "AB12CD")), found)
        assertFalse(found.single().named)
        // Words and plain numbers aren't codes by shape.
        assertTrue(BookingCodes.find("HELLO there 123456").isEmpty())
    }

    @Test fun letters_alone_only_when_named() {
        assertEquals("QXRTZB", BookingCodes.find("PNR: QXRTZB").single().value)
        assertTrue(BookingCodes.find("Order confirmed, thanks").isEmpty())
    }

    @Test fun each_once_and_at_most_a_few() {
        assertEquals(1, BookingCodes.find("Booking ref QX7R2B. Your booking QX7R2B is ready.").size)
        val many = (1..20).joinToString(" ") { "Order no. 10$it-55$it" }
        assertEquals(BookingCodes.MAX, BookingCodes.find(many).size)
    }

    @Test fun grouped_to_read_out() {
        assertEquals("QX7 R2B", BookingCodes.grouped("QX7R2B"))
        assertEquals("125 2186449031", BookingCodes.grouped("125-2186449031"))
        assertEquals("1142 2869 01", BookingCodes.grouped("1142286901"))
    }
}
