package app.parley.common.photo

import app.parley.common.photo.OriginalPhoto.Keep
import app.parley.common.photo.OriginalPhoto.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalPhotoTest {
    private fun probe(f: ImageFiles.Format?, bytes: Long = 3_000_000, location: Boolean? = false) = OriginalPhoto.Probe(f, bytes, location)
    private val none = OriginalPhoto.Answers()

    @Test fun every_format_is_kept_as_it_is_within_the_limit() {
        for (f in ImageFiles.Format.entries) {
            assertEquals("$f", Keep.COPY, OriginalPhoto.plan(probe(f), none).keep)
            assertNull("$f asks nothing", OriginalPhoto.nextQuestion(probe(f), none))
        }
        assertEquals(Keep.COPY, OriginalPhoto.plan(probe(ImageFiles.Format.JPEG, OriginalPhoto.ASK_ABOVE_BYTES), none).keep)
        assertEquals("not a format Parley names", Keep.REENCODE, OriginalPhoto.plan(probe(null), none).keep)
        assertEquals("empty", Keep.REENCODE, OriginalPhoto.plan(probe(ImageFiles.Format.JPEG, 0), none).keep)
    }

    @Test fun location_is_removed_only_where_it_leaves_the_picture_untouched() {
        assertTrue(OriginalPhoto.plan(probe(ImageFiles.Format.JPEG, location = true), none).stripLocation)
        assertTrue(OriginalPhoto.plan(probe(ImageFiles.Format.PNG, location = true), none).stripLocation)
        assertTrue(OriginalPhoto.plan(probe(ImageFiles.Format.WEBP, location = null), none).stripLocation)
        assertFalse(OriginalPhoto.plan(probe(ImageFiles.Format.HEIC, location = false), none).stripLocation)
    }

    @Test fun a_heic_with_a_location_asks_once_and_dismissing_saves_a_jpeg_without_it() {
        for (f in listOf(ImageFiles.Format.HEIC, ImageFiles.Format.HEIF, ImageFiles.Format.AVIF)) {
            val p = probe(f, location = true)
            assertEquals(OriginalPhoto.Question.LOCATION, OriginalPhoto.nextQuestion(p, none))
            assertEquals("not answered: the safer choice", Keep.REENCODE, OriginalPhoto.plan(p, none).keep)
            assertEquals(Keep.REENCODE, OriginalPhoto.plan(p, OriginalPhoto.Answers(keepLocation = false)).keep)
            val kept = OriginalPhoto.Answers(keepLocation = true)
            assertEquals(OriginalPhoto.Plan(Keep.COPY, false), OriginalPhoto.plan(p, kept))
            assertNull("asked once", OriginalPhoto.nextQuestion(p, kept))
        }
        assertEquals("can't tell: asks", OriginalPhoto.Question.LOCATION, OriginalPhoto.nextQuestion(probe(ImageFiles.Format.HEIC, location = null), none))
    }

    @Test fun a_large_file_asks_whole_or_jpeg() {
        val big = probe(ImageFiles.Format.JPEG, OriginalPhoto.ASK_ABOVE_BYTES + 1)
        assertEquals(OriginalPhoto.Question.LARGE, OriginalPhoto.nextQuestion(big, none))
        assertEquals(Keep.REENCODE, OriginalPhoto.plan(big, none).keep)
        assertEquals(Keep.COPY, OriginalPhoto.plan(big, OriginalPhoto.Answers(keepWhole = true)).keep)
        assertEquals(Keep.REENCODE, OriginalPhoto.plan(big, OriginalPhoto.Answers(keepWhole = false)).keep)
        // A large HEIC with a location: whole, then the location question; a JPEG answers both.
        val heic = probe(ImageFiles.Format.HEIC, OriginalPhoto.ASK_ABOVE_BYTES + 1, location = true)
        assertEquals(OriginalPhoto.Question.LOCATION, OriginalPhoto.nextQuestion(heic, OriginalPhoto.Answers(keepWhole = true)))
        assertNull(OriginalPhoto.nextQuestion(heic, OriginalPhoto.Answers(keepWhole = false)))
        // Over the hard limit: always a JPEG, nothing asked.
        val huge = probe(ImageFiles.Format.JPEG, OriginalPhoto.MAX_BYTES + 1)
        assertNull(OriginalPhoto.nextQuestion(huge, none))
        assertEquals(Keep.REENCODE, OriginalPhoto.plan(huge, OriginalPhoto.Answers(keepWhole = true)).keep)
    }

    @Test fun what_was_kept_is_recorded_truthfully() {
        val copy = OriginalPhoto.Plan(Keep.COPY, true)
        assertEquals(OriginalPhoto.Kept.LOCATION_REMOVED, OriginalPhoto.kept(copy, hadLocation = true, hasLocation = false))
        assertEquals(OriginalPhoto.Kept.AS_PICKED, OriginalPhoto.kept(copy, hadLocation = false, hasLocation = false))
        assertEquals(OriginalPhoto.Kept.LOCATION_KEPT, OriginalPhoto.kept(OriginalPhoto.Plan(Keep.COPY, false), hadLocation = true, hasLocation = true))
        assertEquals(OriginalPhoto.Kept.JPEG, OriginalPhoto.kept(OriginalPhoto.Plan(Keep.REENCODE, false), hadLocation = true, hasLocation = false))
        for (k in OriginalPhoto.Kept.entries) assertEquals(k, OriginalPhoto.Kept.of(k.key))
        assertNull("kept before this was recorded", OriginalPhoto.Kept.of(null))
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
