package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadSearchTest {
    @Test fun broad_search_says_which_field_matched() {
        val extra = BroadSearch.Extra(
            company = "Acme",
            addresses = listOf("12 Rue de la Paix, Paris"),
            note = "Met at the café",
            websites = listOf("anna.dev"),
            handles = listOf("@anna:matrix.org"),
        )
        fun m(q: String) = BroadSearch.match(q, "Anna Smith", listOf("+44 7700 900123"), listOf("anna@x.org"), extra)
        assertEquals(BroadSearch.Field.NAME, m("smith"))
        assertEquals(BroadSearch.Field.NUMBER, m("7700"))
        assertEquals(BroadSearch.Field.EMAIL, m("x.org"))
        assertEquals(BroadSearch.Field.COMPANY, m("acme"))
        assertEquals(BroadSearch.Field.ADDRESS, m("paix"))
        assertEquals(BroadSearch.Field.NOTE, m("cafe"))
        assertEquals(BroadSearch.Field.WEBSITE, m("anna.dev"))
        assertEquals(BroadSearch.Field.HANDLE, m("matrix"))
        assertNull(m("zzz"))
        assertTrue(BroadSearch.explains(BroadSearch.Field.ADDRESS))
        assertFalse(BroadSearch.explains(BroadSearch.Field.NAME))
        assertFalse(BroadSearch.explains(null))
    }

    @Test fun profiles_are_found_by_their_handle_with_or_without_at() {
        val p = Profile(ProfileService.INSTAGRAM, "ana.lima")
        val extra = BroadSearch.Extra(websites = listOf(p.url), profiles = SocialProfiles.searchTerms(p))
        fun m(q: String) = BroadSearch.match(q, "Ana", emptyList(), emptyList(), extra)
        assertEquals(BroadSearch.Field.PROFILE, m("@ana.lima"))
        assertEquals(BroadSearch.Field.PROFILE, m("ana.li"))
        assertEquals(BroadSearch.Field.WEBSITE, m("instagram.com"))
    }
}
