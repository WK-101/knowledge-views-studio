package app.parley.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberInfoTest {
    @Test fun areaNamesStillComeForCountriesThatShipThem() {
        assertEquals("Mountain View, CA", NumberInfo.location("+16502530000", "US", Locale.ENGLISH))
        assertEquals("Berlin", NumberInfo.location("+49301234567", "DE", Locale.ENGLISH))
    }

    @Test fun countriesWithoutAreaNamesFallBackToTheCountry() {
        // Beijing and Sydney landlines: their area files aren't in the APK, so only the country is named.
        assertEquals("China", NumberInfo.location("+861012345678", "CN", Locale.ENGLISH))
        assertEquals("Australia", NumberInfo.location("+61212345678", "AU", Locale.ENGLISH))
        assertEquals("Australien", NumberInfo.location("+61212345678", "AU", Locale.GERMAN))
    }

    @Test fun aForeignLandlineIsNamedByItsCountry() {
        // A Berlin number seen from the US, a Sydney one from Germany (its area file isn't in the APK either way).
        assertEquals("Germany", NumberInfo.location("+49301234567", "US", Locale.ENGLISH))
        assertEquals("Australien", NumberInfo.location("+61212345678", "DE", Locale.GERMAN))
    }

    @Test fun invalidNumbersStillHaveNoPlace() {
        assertNull(NumberInfo.location("+8612", "CN", Locale.ENGLISH))
        assertNull(NumberInfo.location("", "AU", Locale.ENGLISH))
    }
}
