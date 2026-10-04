package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoLanguagesTest {
    @Test fun shippedLanguagesAreAskedAsThemselves() {
        for (l in listOf("en", "de", "es", "fr", "pt", "ar")) assertEquals(l, GeoLanguages.forLanguage(l))
        assertEquals("de", GeoLanguages.forLanguage("DE"))
    }

    @Test fun everythingElseAsksInEnglish() {
        for (l in listOf("zh", "ja", "ru", "it", "hi", "ur", "", null)) assertEquals("en", GeoLanguages.forLanguage(l))
    }

    @Test fun chinaAndAustraliaHaveNoAreaNames() {
        assertEquals(false, GeoLanguages.hasAreaNames(86))
        assertEquals(false, GeoLanguages.hasAreaNames(61))
        for (code in listOf(1, 44, 49, 55, 91)) assertEquals(true, GeoLanguages.hasAreaNames(code))
    }
}
