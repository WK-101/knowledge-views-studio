package app.parley.common.photo

import app.parley.common.photo.ImageFiles.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFilesTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    /** An ISO base media file's start: an `ftyp` box with [major] and [compatible] brands, then a little more. */
    private fun ftyp(major: String, vararg compatible: String): ByteArray {
        val size = 16 + 4 * compatible.size
        val box = bytes(0, 0, 0, size) + ascii("ftyp") + ascii(major) + bytes(0, 0, 0, 0) +
            compatible.fold(ByteArray(0)) { acc, b -> acc + ascii(b) }
        return box + bytes(0, 0, 0, 8) + ascii("meta")
    }

    @Test fun reads_the_format_from_the_bytes() {
        assertEquals(Format.JPEG, ImageFiles.detect(bytes(0xFF, 0xD8, 0xFF, 0xE1, 0, 0x10) + ascii("Exif")))
        assertEquals(Format.JPEG, ImageFiles.detect(bytes(0xFF, 0xD8, 0xFF, 0xDB)))
        assertEquals(Format.PNG, ImageFiles.detect(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)))
        assertEquals(Format.WEBP, ImageFiles.detect(ascii("RIFF") + bytes(0x24, 0, 0, 0) + ascii("WEBPVP8 ")))
        assertEquals(Format.GIF, ImageFiles.detect(ascii("GIF89a") + bytes(1, 0, 1, 0)))
        assertEquals(Format.GIF, ImageFiles.detect(ascii("GIF87a")))
    }

    @Test fun tells_heic_heif_and_avif_apart_by_their_brands() {
        assertEquals(Format.HEIC, ImageFiles.detect(ftyp("heic", "mif1", "heic")))
        // Android's camera writes the generic brand first and HEVC among the compatible ones.
        assertEquals(Format.HEIC, ImageFiles.detect(ftyp("mif1", "mif1", "heic")))
        assertEquals(Format.HEIF, ImageFiles.detect(ftyp("mif1", "mif1")))
        assertEquals(Format.AVIF, ImageFiles.detect(ftyp("avif", "mif1", "miaf")))
        assertNull("a video is no picture", ImageFiles.detect(ftyp("isom", "iso2", "mp41")))
    }

    @Test fun knows_nothing_else() {
        assertNull(ImageFiles.detect(ByteArray(0)))
        assertNull(ImageFiles.detect(bytes(0xFF, 0xD8)))
        assertNull(ImageFiles.detect(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WAVE")))
        assertNull(ImageFiles.detect(ascii("%PDF-1.7")))
        assertNull("too short for its box", ImageFiles.detect(bytes(0, 0, 0, 24) + ascii("ftyp")))
    }

    @Test fun every_format_has_its_type_and_extension() {
        assertEquals("image/jpeg", Format.JPEG.mime)
        assertEquals("image/heic", Format.HEIC.mime)
        assertEquals(Format.entries.size, Format.entries.map { it.extension }.toSet().size)
        assertTrue(Format.entries.all { it.mime.startsWith("image/") })
    }

    @Test fun names_the_file_after_the_person() {
        assertEquals("Ana Lima.jpg", ImageFiles.fileName("Ana Lima", Format.JPEG, "Photo"))
        assertEquals("Ana Lima.heic", ImageFiles.fileName("  Ana   Lima ", Format.HEIC, "Photo"))
        assertEquals("José Núñez.png", ImageFiles.fileName("José Núñez", Format.PNG, "Photo"))
        assertEquals("AC DC.webp", ImageFiles.fileName("AC/DC", Format.WEBP, "Photo"))
        assertEquals("a b c d.jpg", ImageFiles.fileName("a:b*c?\"d\n", Format.JPEG, "Photo"))
        assertEquals("no hidden files", "Ana.jpg", ImageFiles.fileName("..Ana.", Format.JPEG, "Photo"))
        assertEquals("Photo.jpg", ImageFiles.fileName("", Format.JPEG, "Photo"))
        assertEquals("Photo.jpg", ImageFiles.fileName("///", Format.JPEG, "Photo"))
        assertEquals("Photo.jpg", ImageFiles.fileName(null, Format.JPEG, "Photo"))
        assertEquals("Picture.png", ImageFiles.fileName(null, Format.PNG, ""))
    }

    @Test fun cuts_long_names_without_breaking_an_emoji() {
        val long = "A".repeat(ImageFiles.MAX_NAME - 1) + "😀" + "tail"
        val name = ImageFiles.fileName(long, Format.JPEG, "Photo")
        assertEquals("A".repeat(ImageFiles.MAX_NAME - 1) + ".jpg", name)
        val fits = "😀 Ana"
        assertEquals("😀 Ana.jpg", ImageFiles.fileName(fits, Format.JPEG, "Photo"))
    }
}
