package app.parley.common.people

import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesWidgetPlanTest {
    private fun fav(id: Long, name: String, vararg numbers: String, primary: Int = -1, key: String = "k$id", starred: Boolean = true) = ContactSummary(
        id, key, name, photoUri = null, starred = starred,
        phones = numbers.mapIndexed { i, n -> PhoneEntry(n, 2, null, isPrimary = i == primary) },
    )

    @Test fun the_grid_follows_the_widget_size() {
        assertEquals(FavoritesWidgetPlan.Grid(4, 2), FavoritesWidgetPlan.grid(0, 0))
        assertEquals(FavoritesWidgetPlan.Grid(1, 1), FavoritesWidgetPlan.grid(40, 40))
        assertEquals(FavoritesWidgetPlan.Grid(4, 2), FavoritesWidgetPlan.grid(300, 200))
        assertEquals(FavoritesWidgetPlan.Grid(5, 3), FavoritesWidgetPlan.grid(360, 280))
        // Tablets: capped.
        assertEquals(FavoritesWidgetPlan.Grid(FavoritesWidgetPlan.MAX_COLUMNS, FavoritesWidgetPlan.MAX_ROWS), FavoritesWidgetPlan.grid(2000, 2000))
    }

    @Test fun before_android_12_portrait_and_landscape_each_get_their_own_size() {
        // A 4×2 widget on a phone: about 300 dp wide in portrait, 500+ dp in landscape, and the reverse for height.
        val l = FavoritesWidgetPlan.layouts(minWidth = 300, minHeight = 110, maxWidth = 520, maxHeight = 190, listed = null)
        l as FavoritesWidgetPlan.Layouts.ByOrientation
        assertEquals(FavoritesWidgetPlan.Size(300, 190), l.portrait)
        assertEquals(FavoritesWidgetPlan.Size(520, 110), l.landscape)
        // Never both maximums: portrait doesn't get landscape's 7 columns, landscape doesn't get portrait's rows.
        assertEquals(FavoritesWidgetPlan.Grid(4, 2), FavoritesWidgetPlan.grid(l.portrait.widthDp, l.portrait.heightDp))
        assertEquals(FavoritesWidgetPlan.Grid(FavoritesWidgetPlan.MAX_COLUMNS, 1), FavoritesWidgetPlan.grid(l.landscape.widthDp, l.landscape.heightDp))
        assertEquals(FavoritesWidgetPlan.Grid(FavoritesWidgetPlan.MAX_COLUMNS, 2), FavoritesWidgetPlan.grid(520, 190))
    }

    @Test fun from_android_12_the_listed_sizes_are_drawn() {
        val listed = listOf(FavoritesWidgetPlan.Size(300, 190), FavoritesWidgetPlan.Size(520, 110), FavoritesWidgetPlan.Size(300, 190))
        val l = FavoritesWidgetPlan.layouts(300, 110, 520, 190, listed) as FavoritesWidgetPlan.Layouts.Listed
        assertEquals(listOf(FavoritesWidgetPlan.Size(300, 190), FavoritesWidgetPlan.Size(520, 110)), l.sizes)
        // An empty or unusable list falls back to the orientations.
        assertTrue(FavoritesWidgetPlan.layouts(300, 110, 520, 190, emptyList()) is FavoritesWidgetPlan.Layouts.ByOrientation)
        assertTrue(FavoritesWidgetPlan.layouts(300, 110, 520, 190, listOf(FavoritesWidgetPlan.Size(0, 0))) is FavoritesWidgetPlan.Layouts.ByOrientation)
        val many = (1..40).map { FavoritesWidgetPlan.Size(100 + it, 100) }
        assertEquals(FavoritesWidgetPlan.MAX_LISTED, (FavoritesWidgetPlan.layouts(0, 0, 0, 0, many) as FavoritesWidgetPlan.Layouts.Listed).sizes.size)
    }

    @Test fun favourites_keep_their_order_and_fill_the_grid() {
        val favs = (1L..10L).map { fav(it, "P$it", "+4420700000$it") }
        val shown = FavoritesWidgetPlan.shown(favs, FavoritesWidgetPlan.Grid(4, 2), locked = false) as FavoritesWidgetPlan.Shown.Tiles
        assertEquals((1L..8L).toList(), shown.tiles.map { it.contactId })
        assertEquals(2, shown.more)
    }

    @Test fun a_tap_calls_the_default_number_or_the_first() {
        val tiles = FavoritesWidgetPlan.select(listOf(fav(1, "Ana", "111", "222", primary = 1), fav(2, "Sam", "333"), fav(3, "Lee")), 9)
        assertEquals("222", tiles[0].number)
        assertEquals("333", tiles[1].number)
        // No number: still shown, the tap opens the page.
        assertNull(tiles[2].number)
    }

    @Test fun private_contacts_and_unstarred_ones_never_reach_the_launcher() {
        val favs = listOf(
            fav(1, "Ana", "111"),
            fav(-5, "Hidden", "222", key = ContactRef.privateKey(5)),
            fav(7, "Also hidden", "333", key = ContactRef.privateKey(7)),
            fav(8, "Not starred", "444", starred = false),
        )
        val shown = FavoritesWidgetPlan.shown(favs, FavoritesWidgetPlan.Grid(4, 2), locked = false) as FavoritesWidgetPlan.Shown.Tiles
        assertEquals(listOf("Ana"), shown.tiles.map { it.name })
        assertEquals(0, shown.more)
        // Nor in the locked count.
        assertEquals(FavoritesWidgetPlan.Shown.Locked(1), FavoritesWidgetPlan.shown(favs, FavoritesWidgetPlan.Grid(4, 2), locked = true))
    }

    @Test fun a_locked_phone_shows_only_the_count() {
        val favs = listOf(fav(1, "Ana", "111"), fav(2, "Sam", "222"))
        val shown = FavoritesWidgetPlan.shown(favs, FavoritesWidgetPlan.Grid(4, 2), locked = true)
        assertEquals(FavoritesWidgetPlan.Shown.Locked(2), shown)
        assertTrue(shown.toString().contains("2") && !shown.toString().contains("Ana"))
    }

    @Test fun no_favourites_is_its_own_state() {
        assertEquals(FavoritesWidgetPlan.Shown.Empty, FavoritesWidgetPlan.shown(emptyList(), FavoritesWidgetPlan.Grid(4, 2), locked = false))
        assertEquals(emptyList<FavoritesWidgetPlan.Tile>(), FavoritesWidgetPlan.select(listOf(fav(1, "Ana")), 0))
    }
}
