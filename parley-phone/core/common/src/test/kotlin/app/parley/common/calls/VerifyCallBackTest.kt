package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Test

class VerifyCallBackTest {
    private val mobile = VerifyCallBack.Saved("Ana", "+44 7700 900123", "Mobile")
    private val home = VerifyCallBack.Saved("Ana", "020 7946 0000", "Home")

    @Test fun the_number_that_called_comes_first_and_each_line_once() {
        val again = VerifyCallBack.Saved("Ana", "07700 900123", "Mobile")
        val list = VerifyCallBack.choices(listOf(home, mobile, again), caller = "+447700900123", countryIso = "GB")
        assertEquals(listOf(mobile, home), list)
    }

    @Test fun without_a_caller_the_order_stays() {
        assertEquals(listOf(home, mobile), VerifyCallBack.choices(listOf(home, mobile), caller = null, countryIso = "GB"))
    }

    @Test fun numbers_without_digits_are_left_out() {
        assertEquals(listOf(mobile), VerifyCallBack.choices(listOf(VerifyCallBack.Saved("Ana", "sip:ana"), mobile), null, "GB"))
    }

    @Test fun organisations_only_by_name() {
        val bank = VerifyCallBack.Saved("My bank", "0345 600 0000", organisation = true)
        val gp = VerifyCallBack.Saved("Clinic", "020 7946 0001", organisation = true)
        assertEquals(listOf(gp, bank), VerifyCallBack.organisations(listOf(bank, mobile, gp), "GB"))
    }
}
