package app.parley.data

import app.parley.common.GeoLanguages
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun invalidNumbersStillHaveNoPlace() {
        assertNull(NumberInfo.location("+8612", "CN", Locale.ENGLISH))
        assertNull(NumberInfo.location("", "AU", Locale.ENGLISH))
    }

    @Test fun theApkDropsExactlyTheseCountriesAreaFiles() {
        val gradle = File("../../app/build.gradle.kts").readText()
        val line = Regex("""listOf\(([^)]*)\)\.map \{ "com/google/i18n/phonenumbers/geocoding/data/\$\{it\}_\*" \}""").find(gradle)
        assertTrue("app/build.gradle.kts no longer drops any area file", line != null)
        val dropped = Regex("\"(\\d+)\"").findAll(line!!.groupValues[1]).map { it.groupValues[1].toInt() }.toSet()
        assertEquals(GeoLanguages.COUNTRIES_WITHOUT_AREAS, dropped)
    }
}
