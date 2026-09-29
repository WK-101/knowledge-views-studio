package app.parley.common.photo

import app.parley.common.photo.OriginalPhoto.Keep
import app.parley.common.photo.OriginalPhoto.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalPhotoTest {
    @Test fun keeps_common_formats_as_they_are_within_the_size_limit() {
        assertEquals(Keep.COPY, OriginalPhoto.keep("image/jpeg", 4_000_000))
        assertEquals(Keep.COPY, OriginalPhoto.keep("IMAGE/PNG", 1))
        assertEquals(Keep.COPY, OriginalPhoto.keep("image/webp", OriginalPhoto.MAX_BYTES))
        assertEquals(Keep.REENCODE, OriginalPhoto.keep("image/jpeg", OriginalPhoto.MAX_BYTES + 1))
        assertEquals(Keep.REENCODE, OriginalPhoto.keep("image/heic", 3_000_000))
        assertEquals(Keep.REENCODE, OriginalPhoto.keep(null, 3_000_000))
        assertEquals(Keep.REENCODE, OriginalPhoto.keep("image/jpeg", 0))
    }

    @Test fun reencoding_keeps_the_aspect_and_bounds_the_pixels() {
        assertEquals(4000 to 3000, OriginalPhoto.reencodeSize(4000, 3000))
        val (w, h) = OriginalPhoto.reencodeSize(8160, 6120)
        assertTrue(w.toLong() * h <= OriginalPhoto.MAX_REENCODE_PIXELS)
        assertEquals(8160.0 / 6120, w.toDouble() / h, 0.01)
        assertEquals(1 to 1, OriginalPhoto.reencodeSize(0, -3))
    }

    @Test fun the_hero_is_round_when_square_and_keeps_the_shape_otherwise() {
        assertEquals(OriginalPhoto.Hero(true, 1f, 1f), OriginalPhoto.hero(1000, 1050))
        val wide = OriginalPhoto.hero(4000, 3000)
        assertFalse(wide.round)
        assertEquals(4f / 3, wide.width, 0.001f)
        assertEquals(1f, wide.height)
        // A panorama is capped.
        assertEquals(1.6f, OriginalPhoto.hero(9000, 2000).width)
        val tall = OriginalPhoto.hero(3000, 4000)
        assertEquals(1.25f, tall.height)
        assertEquals(1.25f * 0.75f, tall.width, 0.001f)
        assertEquals(1.25f * 0.6f, OriginalPhoto.hero(1000, 4000).width, 0.001f)
    }

    @Test fun regions_map_back_to_the_stored_image_for_every_orientation() {
        val w = 40
        val h = 30
        for (o in 1..8) {
            val t = PhotoMath.exifTransform(o)
            // Every pixel round-trips through map and unmap.
            for ((x, y) in listOf(0 to 0, 39 to 29, 5 to 7, 39 to 0, 0 to 29)) {
                val (ux, uy) = t.map(x, y, w, h)
                assertEquals("orientation $o", x to y, OriginalPhoto.unmap(ux, uy, t, w, h))
            }
            // A region of the upright image covers exactly the stored pixels that land in it.
            val (uw, uh) = t.uprightSize(w, h)
            val region = PhotoMath.Crop(uw / 4, uh / 3, uw / 2, uh - 2)
            val stored = OriginalPhoto.storedRegion(region, t, w, h)
            assertEquals(region.width.toLong() * region.height, stored.width.toLong() * stored.height)
            for (x in stored.left until stored.right) for (y in stored.top until stored.bottom) {
                val (ux, uy) = t.map(x, y, w, h)
                assertTrue(ux in region.left until region.right && uy in region.top until region.bottom)
            }
        }
    }

    @Test fun the_viewer_decodes_just_enough_pixels() {
        assertEquals(1, OriginalPhoto.sampleFor(1000, 800, 1080, 2000))
        assertEquals(4, OriginalPhoto.sampleFor(8000, 6000, 1080, 1080))
        assertEquals(1, OriginalPhoto.sampleFor(0, 0, 10, 10))
    }

    @Test fun an_original_goes_stale_when_another_app_replaces_the_photo() {
        // Android hasn't processed the new photo yet: show the original.
        assertEquals(Match.Show(), OriginalPhoto.match("content://p/1", null, "content://p/1"))
        assertEquals(Match.Show(), OriginalPhoto.match(null, null, null))
        // The new photo's URI appears: remember it.
        assertEquals(Match.Show("content://p/2"), OriginalPhoto.match("content://p/1", "", "content://p/2"))
        assertEquals(Match.Show(), OriginalPhoto.match("content://p/1", "content://p/2", "content://p/2"))
        // Replaced or removed elsewhere.
        assertEquals(Match.Stale, OriginalPhoto.match("content://p/1", "content://p/2", "content://p/3"))
        assertEquals(Match.Stale, OriginalPhoto.match(null, "content://p/2", null))
    }
}
