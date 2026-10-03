package app.parley.common.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PhotoFrameTest {
    private fun assertInside(c: PhotoMath.Crop, w: Int, h: Int) {
        assertTrue("$c inside $w×$h", c.left >= 0 && c.top >= 0 && c.right <= w && c.bottom <= h)
        assertEquals("square: $c", c.width, c.height)
    }

    private fun near(a: Double, b: Double, eps: Double = 1e-6) = assertTrue("$a ≈ $b", abs(a - b) <= eps)

    // ---------------------------------------------------------------- default and stored form

    @Test fun the_default_frame_is_the_largest_centred_square() {
        assertEquals(PhotoMath.Crop(500, 0, 3500, 3000), FrameMath.toCrop(FrameMath.centred(4000, 3000), 4000, 3000))
        assertEquals(PhotoMath.Crop(0, 500, 3000, 3500), FrameMath.toCrop(FrameMath.centred(3000, 4000), 3000, 4000))
        assertEquals(PhotoMath.Crop(0, 0, 720, 720), FrameMath.toCrop(FrameMath.centred(720, 720), 720, 720))
        // The same frame as Android's centre-cropped thumbnail.
        assertEquals(PhotoMath.centerSquare(4000, 3000), FrameMath.toCrop(FrameMath.centred(4000, 3000), 4000, 3000))
    }

    @Test fun a_frame_round_trips_through_its_stored_form() {
        val f = PhotoFrame(0.12345, 0.5, 0.75)
        assertEquals(f, PhotoFrame.decode(f.encode()))
        assertEquals("0.12345,0.50000,0.75000", f.encode())
        assertNull(PhotoFrame.decode(null))
        assertNull(PhotoFrame.decode(""))
        assertNull(PhotoFrame.decode("1,2"))
        assertNull(PhotoFrame.decode("a,b,c"))
        assertNull(PhotoFrame.decode("0,0,0"))
        assertNull(PhotoFrame.decode("0,0,NaN"))
    }

    @Test fun a_frame_fits_any_decoded_size_of_the_same_picture() {
        val f = PhotoFrame(0.1, 0.2, 0.5)
        val full = FrameMath.toCrop(f, 4000, 3000)
        val small = FrameMath.toCrop(f, 1000, 750)
        assertEquals(full.left / 4.0, small.left.toDouble(), 1.0)
        assertEquals(full.top / 4.0, small.top.toDouble(), 1.0)
        assertEquals(full.width / 4.0, small.width.toDouble(), 1.0)
    }

    // ---------------------------------------------------------------- clamping

    @Test fun clamping_keeps_the_square_inside_the_picture() {
        val sizes = listOf(4000 to 3000, 3000 to 4000, 100 to 100, 37 to 2000, 1 to 1)
        val frames = listOf(
            PhotoFrame(-1.0, -1.0, 1.0), PhotoFrame(2.0, 2.0, 1.0), PhotoFrame(0.9, 0.9, 0.3), PhotoFrame(0.0, 0.0, 5.0),
            PhotoFrame(0.5, 0.5, 0.0001),
        )
        for ((w, h) in sizes) for (f in frames) assertInside(FrameMath.toCrop(f, w, h), w, h)
    }

    @Test fun clamping_limits_the_zoom() {
        // At most 8× on a large picture...
        val c = FrameMath.clamp(PhotoFrame(0.0, 0.0, 0.01), 4000, 3000)
        near(1.0 / FrameMath.MAX_ZOOM, c.side)
        // ...and never below 48 px on a small one (or the whole picture when it is smaller than that).
        assertEquals(48, FrameMath.toCrop(PhotoFrame(0.0, 0.0, 0.01), 200, 100).width)
        assertEquals(30, FrameMath.toCrop(PhotoFrame(0.0, 0.0, 0.01), 30, 40).width)
        // Larger than the shorter side: the largest square.
        near(1.0, FrameMath.clamp(PhotoFrame(0.0, 0.0, 3.0), 4000, 3000).side)
    }

    @Test fun clamping_fixes_broken_numbers() {
        val c = FrameMath.clamp(PhotoFrame(Double.NaN, Double.NaN, Double.NaN), 400, 300)
        assertInside(FrameMath.toCrop(c, 400, 300), 400, 300)
        assertEquals(PhotoMath.Crop(0, 0, 0, 0), FrameMath.toCrop(PhotoFrame(0.0, 0.0, 1.0), 0, 300))
    }

    @Test fun crops_and_frames_convert_both_ways() {
        val crop = PhotoMath.Crop(1000, 500, 2500, 2000)
        assertEquals(crop, FrameMath.toCrop(FrameMath.fromCrop(crop, 4000, 3000), 4000, 3000))
    }

    // ---------------------------------------------------------------- gesture and buttons

    @Test fun dragging_moves_the_photo_with_the_finger() {
        val start = PhotoFrame(0.25, 0.0, 0.5) // 4000×3000: a 1500 px square at (1000, 0)
        // The square fills a 1500 px viewport (1 screen px per picture px); drag the photo 100 px right and down.
        val f = FrameMath.gesture(start, 4000, 3000, 1500.0, 100.0, 100.0, 1.0, 750.0, 750.0)
        val c = FrameMath.toCrop(f, 4000, 3000)
        // Shows more of the left and top: the square moved left and up (the top is already at the edge).
        assertEquals(900, c.left)
        assertEquals(0, c.top)
        assertEquals(1500, c.width)
    }

    @Test fun pinching_keeps_the_point_under_the_fingers() {
        val start = FrameMath.centred(4000, 3000) // 3000 px square at (500, 0)
        val view = 1000.0
        // Pinch 2× around the viewport's centre: the picture point (2000, 1500) stays in the middle.
        val f = FrameMath.gesture(start, 4000, 3000, view, 0.0, 0.0, 2.0, 500.0, 500.0)
        val c = FrameMath.toCrop(f, 4000, 3000)
        assertEquals(1500, c.width)
        assertEquals(2000, (c.left + c.right) / 2)
        assertEquals(1500, (c.top + c.bottom) / 2)
    }

    @Test fun pinching_out_past_the_whole_picture_stops_there() {
        val f = FrameMath.gesture(FrameMath.centred(4000, 3000), 4000, 3000, 1000.0, 0.0, 0.0, 0.25, 0.0, 0.0)
        near(1.0, f.side)
        assertInside(FrameMath.toCrop(f, 4000, 3000), 4000, 3000)
    }

    @Test fun zoom_buttons_zoom_around_the_square_centre() {
        val start = FrameMath.centred(4000, 3000)
        val inFrame = FrameMath.zoomBy(start, 4000, 3000, 2.0)
        val c = FrameMath.toCrop(inFrame, 4000, 3000)
        assertEquals(PhotoMath.Crop(1250, 750, 2750, 2250), c)
        near(2.0, FrameMath.zoomOf(inFrame))
        // Back out again: the whole square.
        assertEquals(FrameMath.toCrop(start, 4000, 3000), FrameMath.toCrop(FrameMath.zoomBy(inFrame, 4000, 3000, 0.5), 4000, 3000))
    }

    @Test fun move_buttons_move_the_photo_like_dragging() {
        val start = FrameMath.zoomBy(FrameMath.centred(4000, 3000), 4000, 3000, 2.0) // 1500 px at (1250, 750)
        val right = FrameMath.toCrop(FrameMath.move(start, 4000, 3000, FrameMath.MOVE_STEP, 0.0), 4000, 3000)
        assertEquals(1100, right.left) // the photo moves right, so the square shows more of its left
        val down = FrameMath.toCrop(FrameMath.move(start, 4000, 3000, 0.0, FrameMath.MOVE_STEP), 4000, 3000)
        assertEquals(600, down.top)
        // Far past the edge: stops there.
        val edge = FrameMath.toCrop(FrameMath.move(start, 4000, 3000, -100.0, -100.0), 4000, 3000)
        assertEquals(PhotoMath.Crop(2500, 1500, 4000, 3000), edge)
    }

    @Test fun centring_on_a_face_keeps_the_size_and_stays_inside() {
        // A portrait with the face high up: the square moves up to it.
        val portrait = FrameMath.toCrop(FrameMath.centreOn(FrameMath.centred(3000, 4000), 3000, 4000, 1500.0, 900.0), 3000, 4000)
        assertEquals(PhotoMath.Crop(0, 0, 3000, 3000), portrait)
        // A face near the right edge of a zoomed frame.
        val f = FrameMath.zoomBy(FrameMath.centred(4000, 3000), 4000, 3000, 2.0)
        val c = FrameMath.toCrop(FrameMath.centreOn(f, 4000, 3000, 3900.0, 1500.0), 4000, 3000)
        assertEquals(PhotoMath.Crop(2500, 750, 4000, 2250), c)
    }

    @Test fun same_compares_to_the_pixel() {
        assertTrue(FrameMath.same(PhotoFrame(0.1, 0.0, 0.5), PhotoFrame(0.100001, 0.0, 0.5), 4000, 3000))
    }

    // ---------------------------------------------------------------- rotation (EXIF) and decode size

    @Test fun the_stored_region_follows_the_exif_rotation() {
        // Stored 4000×3000 (landscape sensor), EXIF 6: upright 3000×4000. The top square of the upright picture...
        val top = PhotoFrame(0.0, 0.0, 1.0)
        val stored = FrameMath.storedCrop(top, 6, 4000, 3000)
        // ...is the left part of the stored file (rotated 90° clockwise to stand up).
        assertEquals(PhotoMath.Crop(0, 0, 3000, 3000), stored)
        val bottom = FrameMath.storedCrop(PhotoFrame(0.0, 0.25, 1.0), 6, 4000, 3000)
        assertEquals(PhotoMath.Crop(1000, 0, 4000, 3000), bottom)
        // EXIF 8 turns the other way: the upright top is the stored right part.
        assertEquals(PhotoMath.Crop(1000, 0, 4000, 3000), FrameMath.storedCrop(top, 8, 4000, 3000))
        // No rotation: as is.
        assertEquals(PhotoMath.Crop(500, 0, 3500, 3000), FrameMath.storedCrop(FrameMath.centred(4000, 3000), 1, 4000, 3000))
    }

    @Test fun the_stored_region_follows_a_mirror() {
        // EXIF 2 mirrors: the upright left square is the stored right square.
        assertEquals(PhotoMath.Crop(1000, 0, 4000, 3000), FrameMath.storedCrop(PhotoFrame(0.0, 0.0, 1.0), 2, 4000, 3000))
    }

    @Test fun the_decode_size_leaves_the_avatar_its_full_pixels() {
        // The whole square of a 4000×3000 picture: 720 px short side is enough, so 960 long.
        assertEquals(960, FrameMath.decodeLongSide(4000, 3000, 1.0))
        // A quarter-size square needs four times as many.
        assertEquals(3840, FrameMath.decodeLongSide(4000, 3000, 0.25))
        // Never more than the picture has, or the cap.
        assertEquals(4000, FrameMath.decodeLongSide(4000, 3000, 0.125, cap = 9000))
        assertEquals(2048, FrameMath.decodeLongSide(8000, 6000, 0.125, cap = 2048))
        assertEquals(FrameMath.decodeLongSide(4000, 3000, 0.5), FrameMath.decodeLongSide(4000, 3000, 0.5))
        assertEquals(720, FrameMath.outputSide(3000))
        assertEquals(300, FrameMath.outputSide(300))
    }
}
