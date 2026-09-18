package com.cairn.reader.domain.dedupe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimHashTest {

    private val article = "The committee released its long-awaited report on urban transit funding. " +
        "It recommends dedicated bus lanes, off-board fare collection, and signal priority to cut " +
        "commute times across the metropolitan area over the next decade. Planners estimate the " +
        "package would move an additional forty thousand riders each weekday while lowering emissions " +
        "and easing congestion on the busiest downtown corridors. Council members from the eastern " +
        "districts pressed for firm timelines and independent audits before any bonds are issued, and " +
        "advocacy groups welcomed the emphasis on accessibility, frequent service, and protected " +
        "crossings near schools, clinics, and the regional hospital campus."

    @Test fun `identical text hashes identically`() {
        assertEquals(SimHash.compute(article), SimHash.compute(article))
    }

    @Test fun `case and whitespace do not change the fingerprint`() {
        val a = SimHash.compute(article)
        val b = SimHash.compute(article.uppercase().replace(" ", "   "))
        assertEquals(a, b)
    }

    @Test fun `a small edit stays within the near-dup Hamming bound`() {
        val a = SimHash.compute(article)
        val b = SimHash.compute("$article Read the full story on our website.")
        assertTrue("hamming ${SimHash.hamming(a, b)} should be <= ${SimHash.NEAR_DUP_HAMMING}",
            SimHash.isNearDuplicate(a, b))
    }

    @Test fun `unrelated texts are far apart`() {
        val a = SimHash.compute(article)
        val b = SimHash.compute("A dessert recipe: whisk eggs and sugar, fold in flour, bake at 180C.")
        assertFalse(SimHash.isNearDuplicate(a, b))
        assertTrue(SimHash.hamming(a, b) > SimHash.NEAR_DUP_HAMMING)
    }

    @Test fun `empty and trivial text returns the not-computed sentinel`() {
        assertEquals(0L, SimHash.compute(""))
        assertEquals(0L, SimHash.compute("a an "))   // all tokens below the min length
    }

    @Test fun `sentinel zero is never a match`() {
        assertFalse(SimHash.isNearDuplicate(0L, 0L))
        assertFalse(SimHash.isNearDuplicate(0L, SimHash.compute(article)))
    }

    @Test fun `real fingerprint is never zero`() {
        assertNotEquals(0L, SimHash.compute(article))
    }

    @Test fun `bands yield distinct tagged keys and share one when near-dup`() {
        val a = SimHash.compute(article)
        val b = SimHash.compute("$article One more line.")
        assertEquals(SimHash.BAND_COUNT, SimHash.bands(a).size)
        // Pigeonhole: within NEAR_DUP_HAMMING differing bits over 4 bands, at least one band is equal.
        val shared = SimHash.bands(a).toSet().intersect(SimHash.bands(b).toSet())
        assertTrue("near-dups must share at least one band", shared.isNotEmpty())
    }

    @Test fun `band tags separate equal chunks in different positions`() {
        // 0 hashes to all-equal 16-bit chunks; the band index tag must keep them distinct.
        val bands = SimHash.bands(0L)
        assertEquals(bands.size, bands.toSet().size)
    }
}
