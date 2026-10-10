package app.parley.common.phone

import app.parley.common.GeoLanguages
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.io.DataInputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packed libphonenumber data (PhoneData, built by packPhoneData) holds exactly what the library ships, and reading
 * it gives the library's own answers. The original class-path files are still on the test class path, so each check
 * compares the pack with them.
 */
class PhoneDataTest {
    private fun jarOf(c: Class<*>) = ZipFile(File(c.protectionDomain.codeSource.location.toURI()))

    private fun files(c: Class<*>, prefix: String): Map<String, ByteArray> = jarOf(c).use { zip ->
        zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(prefix) }
            .associate { e -> e.name to zip.getInputStream(e).use { it.readBytes() } }
    }

    @Test fun every_metadata_file_is_in_the_pack_byte_for_byte() {
        val originals = files(PhoneNumberUtil::class.java, "com/google/i18n/phonenumbers/data/")
            .filterKeys { "PhoneNumberMetadataProto_" in it || "ShortNumberMetadataProto_" in it }
        assertTrue(originals.size > 400)
        for ((name, bytes) in originals) {
            val packed = PhoneData.loader.loadMetadata("/$name")?.use { it.readBytes() }
            assertArrayEquals(name, bytes, packed)
        }
        assertNull(PhoneData.loader.loadMetadata("/com/google/i18n/phonenumbers/data/PhoneNumberMetadataProto_XX"))
    }

    @Test fun the_area_names_are_those_of_the_shipped_languages_without_the_left_out_countries() {
        val file = Regex("""(\d+)_(\w+)""")
        val expected = files(PhoneNumberOfflineGeocoder::class.java, "com/google/i18n/phonenumbers/geocoding/data/")
            .mapKeys { it.key.substringAfterLast('/') }
            .filterKeys { name ->
                val m = file.matchEntire(name) ?: return@filterKeys false
                m.groupValues[2] in GeoLanguages.SHIPPED && m.groupValues[1].toInt() !in GeoLanguages.COUNTRIES_WITHOUT_AREAS
            }
        val packed = DataInputStream(PhoneData::class.java.getResourceAsStream(PhoneData.AREA_NAMES)!!).use { d ->
            List(d.readInt()) { d.readUTF().also { d.readInt() } }
        }
        assertEquals(expected.keys.sorted(), packed)
        for ((name, bytes) in expected.entries.take(40)) assertArrayEquals(name, bytes, PhoneData.indexedEntry(PhoneData.AREA_NAMES, name))
    }

    @Test fun the_library_and_short_numbers_read_the_pack() {
        assertSame(PhoneData.util, PhoneNumberUtil.getInstance())
        assertTrue(PhoneData.shortNumbers.isEmergencyNumber("112", "DE"))
        assertTrue(PhoneData.shortNumbers.isEmergencyNumber("911", "US"))
        assertEquals("+49 30 123456", PhoneData.util.format(PhoneData.util.parse("030 123456", "DE"), PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL))
    }

    @Test fun area_names_are_the_geocoders_own() {
        val util = PhoneData.util
        val geocoder = PhoneNumberOfflineGeocoder.getInstance()
        val numbers = util.supportedRegions.sorted().flatMap { region ->
            listOfNotNull(
                util.getExampleNumber(region),
                util.getExampleNumberForType(region, PhoneNumberUtil.PhoneNumberType.MOBILE),
            ).map { region to it }
        } + listOf("+16502530000", "+12125550100", "+5491123456789", "+5493514567890", "+441614960000").map { n ->
            val p = util.parse(n, "ZZ")
            util.getRegionCodeForNumber(p) to p
        }
        var compared = 0
        for ((region, number) in numbers) {
            if (!GeoLanguages.hasAreaNames(number.countryCode)) continue
            for (lang in GeoLanguages.SHIPPED) {
                val locale = Locale.forLanguageTag(lang)
                for (userRegion in listOf(region, "US")) {
                    assertEquals("$number in $lang from $userRegion", geocoder.getDescriptionForNumber(number, locale, userRegion), AreaNames.describe(number, locale, userRegion))
                    compared++
                }
            }
        }
        assertTrue(compared > 2000)
    }
}
