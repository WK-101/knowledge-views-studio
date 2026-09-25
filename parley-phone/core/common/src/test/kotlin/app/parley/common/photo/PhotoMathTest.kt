package app.parley.common.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoMathTest {
    // ---------------------------------------------------------------- sample size

    @Test fun sample_size_keeps_the_shorter_side_at_or_above_the_target() {
        assertEquals(1, PhotoMath.sampleSize(720, 720))
        assertEquals(1, PhotoMath.sampleSize(1439, 2000))
        assertEquals(2, PhotoMath.sampleSize(1440, 2000))
        // A 50 MP photo (8160×6120) decodes at 1/8: 1020×765, still ≥ 720 on the short side.
        assertEquals(8, PhotoMath.sampleSize(8160, 6120))
        assertTrue(6120 / PhotoMath.sampleSize(8160, 6120) >= PhotoMath.TARGET)
        // Portrait or landscape doesn't matter.
        assertEquals(PhotoMath.sampleSize(4000, 3000), PhotoMath.sampleSize(3000, 4000))
    }

    @Test fun sample_size_is_one_for_small_or_broken_sizes() {
        assertEquals(1, PhotoMath.sampleSize(100, 50))
        assertEquals(1, PhotoMath.sampleSize(0, 5000))
        assertEquals(1, PhotoMath.sampleSize(-1, -1))
    }

    // ---------------------------------------------------------------- decode size

    @Test fun scaled_size_puts_the_shorter_side_on_the_target() {
        assertEquals(960 to 720, PhotoMath.scaledSize(4000, 3000))
        assertEquals(720 to 960, PhotoMath.scaledSize(3000, 4000))
        assertEquals(720 to 720, PhotoMath.scaledSize(5000, 5000))
        // Very wide panoramas keep their shape.
        assertEquals(2880 to 720, PhotoMath.scaledSize(12000, 3000))
    }

    @Test fun scaled_size_never_enlarges() {
        assertEquals(400 to 300, PhotoMath.scaledSize(400, 300))
        assertEquals(720 to 1000, PhotoMath.scaledSize(720, 1000))
    }

    // ---------------------------------------------------------------- crop

    @Test fun center_square_cuts_evenly_from_the_long_side() {
        assertEquals(PhotoMath.Crop(120, 0, 840, 720), PhotoMath.centerSquare(960, 720))
        assertEquals(PhotoMath.Crop(0, 120, 720, 840), PhotoMath.centerSquare(720, 960))
        assertEquals(PhotoMath.Crop(0, 0, 500, 500), PhotoMath.centerSquare(500, 500))
        val odd = PhotoMath.centerSquare(721, 720)
        assertEquals(720, odd.width)
        assertEquals(720, odd.height)
    }

    // ---------------------------------------------------------------- EXIF orientation

    @Test fun normal_and_unknown_orientations_leave_the_image_alone() {
        for (o in listOf(0, 1, 9, -1)) assertTrue(PhotoMath.exifTransform(o).isIdentity)
    }

    @Test fun rotations_swap_sides_only_for_quarter_turns() {
        assertEquals(3000 to 4000, PhotoMath.exifTransform(6).uprightSize(4000, 3000))
        assertEquals(3000 to 4000, PhotoMath.exifTransform(8).uprightSize(4000, 3000))
        assertEquals(4000 to 3000, PhotoMath.exifTransform(3).uprightSize(4000, 3000))
        assertFalse(PhotoMath.exifTransform(2).swapsSides)
        assertTrue(PhotoMath.exifTransform(5).swapsSides)
        assertTrue(PhotoMath.exifTransform(7).swapsSides)
    }

    /** Where the stored top-left corner of a 4×3 image ends up, for every EXIF orientation (per the EXIF spec). */
    @Test fun every_orientation_maps_the_top_left_corner_correctly() {
        val w = 4
        val h = 3
        fun corner(o: Int) = PhotoMath.exifTransform(o).map(0, 0, w, h)
        assertEquals(0 to 0, corner(1))
        assertEquals(w - 1 to 0, corner(2)) // mirrored: top-right
        assertEquals(w - 1 to h - 1, corner(3)) // upside down: bottom-right
        assertEquals(0 to h - 1, corner(4)) // flipped vertically: bottom-left
        assertEquals(0 to 0, corner(5)) // transposed: stays top-left
        assertEquals(h - 1 to 0, corner(6)) // turned clockwise: top-right of the 3×4 result
        assertEquals(h - 1 to w - 1, corner(7)) // transverse: bottom-right
        assertEquals(0 to w - 1, corner(8)) // turned anticlockwise: bottom-left
    }

    @Test fun every_orientation_is_a_bijection_onto_the_upright_image() {
        val w = 5
        val h = 3
        for (o in 1..8) {
            val t = PhotoMath.exifTransform(o)
            val (uw, uh) = t.uprightSize(w, h)
            val seen = HashSet<Pair<Int, Int>>()
            for (y in 0 until h) for (x in 0 until w) {
                val p = t.map(x, y, w, h)
                assertTrue("orientation $o maps outside", p.first in 0 until uw && p.second in 0 until uh)
                seen += p
            }
            assertEquals("orientation $o", w * h, seen.size)
        }
    }
}
