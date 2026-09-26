package app.parley.common

/** S1 (v3.3): the keypad as its own tab (as before) or docked at the foot of Recents. */
enum class CallsLayout { SEPARATE, COMBINED }

/** S2 (v3.3): favourites only in their own tab (as before), as a folding section at the top of Contacts, or a strip of avatars. */
enum class FavoritesPlacement { OFF, SECTION, STRIP }

/** S1: what tapping a call in Recents does. Opening the details is the long-standing behaviour. */
enum class RecentTap { OPEN_DETAILS, CALL }

/**
 * S1/S2 (v3.3): optional combined surfaces. Nothing here ever changes on its own: an update keeps Separate / Off
 * (see [migrate]), and merging hides a tab from the bar through [HomeLayout] only, without touching [NavTabs]
 * (so the saved order and "shown" switches come back exactly as they were when the option is switched off).
 */
data class SurfaceLayout(
    val calls: CallsLayout = CallsLayout.SEPARATE,
    /** With [CallsLayout.COMBINED]: also keep the Keypad tab in the bar. */
    val keepKeypadTab: Boolean = false,
    val favorites: FavoritesPlacement = FavoritesPlacement.OFF,
    /** With favourites in Contacts: also keep the Favorites tab in the bar. */
    val keepFavoritesTab: Boolean = false,
    /** A "Frequent" row under the favourites in Contacts. */
    val frequentsRow: Boolean = false,
    /** The favourites section / strip in Contacts is folded (the user's choice, so it's remembered). */
    val favoritesCollapsed: Boolean = false,
    val recentTap: RecentTap = RecentTap.OPEN_DETAILS,
) {
    /** "v=1;calls=COMBINED;keypadTab=0;fav=SECTION;favTab=0;freq=0;favFolded=0;tap=OPEN_DETAILS". */
    fun encode(): String = listOf(
        "v" to SCHEMA.toString(),
        "calls" to calls.name,
        "keypadTab" to keepKeypadTab.bit(),
        "fav" to favorites.name,
        "favTab" to keepFavoritesTab.bit(),
        "freq" to frequentsRow.bit(),
        "favFolded" to favoritesCollapsed.bit(),
        "tap" to recentTap.name,
    ).joinToString(";") { (k, v) -> "$k=$v" }

    /** Whether anything is merged (for "Back to separate tabs"). */
    val merged: Boolean get() = calls != CallsLayout.SEPARATE || favorites != FavoritesPlacement.OFF

    /** One tap back: both surfaces separate again (the row-tap choice is kept; it isn't about layout). */
    fun separated(): SurfaceLayout = copy(calls = CallsLayout.SEPARATE, favorites = FavoritesPlacement.OFF)

    companion object {
        /** Bumped when the stored format or a default changes; [migrate] handles older values. */
        const val SCHEMA = 1

        /**
         * The layout a fresh install starts with. Deliberately the same as before (Separate / Off): the research
         * behind S1/S2 found every vendor that merged tabs by default had to add a way back or partly revert
         * (Google Phone 2025, iOS 26 Unified), so merging stays something people choose.
         */
        val FRESH_INSTALL = SurfaceLayout()

        /** The layout an existing user keeps on update: exactly what they had (the tabs as separate surfaces). */
        val EXISTING_USER = SurfaceLayout()

        private fun Boolean.bit() = if (this) "1" else "0"

        /**
         * Reads [encode]'s format. Unknown keys (from a newer version) are ignored and unknown or missing values
         * fall back to Separate / Off, so a downgrade or a partial backup never merges anything.
         */
        fun decode(value: String?): SurfaceLayout {
            if (value.isNullOrBlank()) return SurfaceLayout()
            val map = value.split(';').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }.toMap()
            val d = SurfaceLayout()
            fun bool(k: String, def: Boolean) = when (map[k]) { "1" -> true; "0" -> false; else -> def }
            return SurfaceLayout(
                calls = CallsLayout.entries.firstOrNull { it.name == map["calls"] } ?: d.calls,
                keepKeypadTab = bool("keypadTab", d.keepKeypadTab),
                favorites = FavoritesPlacement.entries.firstOrNull { it.name == map["fav"] } ?: d.favorites,
                keepFavoritesTab = bool("favTab", d.keepFavoritesTab),
                frequentsRow = bool("freq", d.frequentsRow),
                favoritesCollapsed = bool("favFolded", d.favoritesCollapsed),
                recentTap = RecentTap.entries.firstOrNull { it.name == map["tap"] } ?: d.recentTap,
            )
        }

        /** Schema version of a stored value (0 = none stored yet). */
        fun schemaOf(value: String?): Int =
            value?.split(';')?.firstOrNull { it.trim().startsWith("v=") }?.substringAfter('=')?.trim()?.toIntOrNull() ?: 0

        /**
         * The value to store on start, or null when nothing needs writing. A value from this schema is kept as it
         * is; none at all is pinned explicitly ([EXISTING_USER] when other settings exist, [FRESH_INSTALL] on a new
         * install), so a later change of defaults can never move an existing user's layout.
         */
        fun migrate(stored: String?, existingUser: Boolean): String? {
            val schema = schemaOf(stored)
            return when {
                stored.isNullOrBlank() || schema == 0 -> (if (existingUser) EXISTING_USER else FRESH_INSTALL).encode()
                // Written by a newer version: leave it alone (decode reads what it understands).
                schema >= SCHEMA -> null
                // Older schemas (none yet): re-encode what was chosen, filling in today's values for new fields.
                else -> decode(stored).encode()
            }
        }
    }
}

/**
 * S1/S2 (v3.3): which tabs the bar shows and which surface hosts the keypad, the favourites and the Circle,
 * given [tabs] (the user's order and switches) and [surfaces] (the combine options).
 *
 * A tab is only absorbed while its host tab is shown, so combining can never leave the keypad or the favourites
 * unreachable (hide Recents and the Keypad tab is back). Nothing here edits [tabs].
 */
data class HomeLayout(val tabs: NavTabs = NavTabs(), val surfaces: SurfaceLayout = SurfaceLayout()) {
    /** The keypad is docked at the foot of Recents. */
    val keypadDocked: Boolean get() = surfaces.calls == CallsLayout.COMBINED && tabs.isVisible(StartTab.RECENTS)

    /** The favourites show at the top of Contacts (section or strip). */
    val favoritesInContacts: Boolean get() = surfaces.favorites != FavoritesPlacement.OFF && tabs.isVisible(StartTab.CONTACTS)

    /** Tabs hidden from the bar because another surface shows them. */
    val absorbed: Set<StartTab>
        get() = buildSet {
            if (keypadDocked && !surfaces.keepKeypadTab) add(StartTab.KEYPAD)
            if (favoritesInContacts && !surfaces.keepFavoritesTab) add(StartTab.FAVORITES)
        }

    /** Tabs in the bar, in the user's order. Never empty. */
    val visible: List<StartTab> get() = absorbed.let { a -> tabs.visible.filter { it !in a }.ifEmpty { tabs.visible } }

    fun isVisible(tab: StartTab): Boolean = tab in visible

    /** The tab that shows [tab]'s content: itself, or the surface that absorbed it. */
    fun hostOf(tab: StartTab): StartTab = when {
        tab == StartTab.KEYPAD && tab in absorbed -> StartTab.RECENTS
        tab == StartTab.FAVORITES && tab in absorbed -> StartTab.CONTACTS
        else -> tab
    }

    /** A request for the keypad (tel:, ACTION_DIAL, shortcuts, headset) opens the docked keypad in Recents. */
    fun opensDockedKeypad(tab: StartTab): Boolean = tab == StartTab.KEYPAD && hostOf(tab) == StartTab.RECENTS

    /** "Open on": [preferred] (or the surface hosting it) when shown, else the first tab in the bar. */
    fun startTab(preferred: StartTab): StartTab = hostOf(preferred).let { if (it in visible) it else visible.first() }

    /**
     * The tab to ask for when opening on [preferred]: [preferred] itself while its surface is shown (so a Keypad
     * start tab still unfolds the docked keypad), else the first tab in the bar.
     */
    fun startRequest(preferred: StartTab): StartTab = if (hostOf(preferred) in visible) preferred else visible.first()

    /** Items for the bar while [current] is open: the bar's tabs, plus [current] in its place if it's hidden. */
    fun barTabs(current: StartTab): List<StartTab> = tabs.order.filter { it in visible || it == current }

    /** With a single item there is nothing to switch between: the bar (or rail) hides. */
    fun showBar(current: StartTab): Boolean = barTabs(current).size > 1

    /**
     * R1: where the Circle section lives while the Circle tab is hidden: at the top of Favourites as before, or,
     * when Favourites is folded into Contacts (and its tab not kept), in Contacts under the favourites.
     */
    val circleHost: StartTab?
        get() = when {
            tabs.isVisible(StartTab.CIRCLE) -> null
            StartTab.FAVORITES in absorbed -> StartTab.CONTACTS
            else -> StartTab.FAVORITES
        }
}

/** S1/S2: the home layout these settings describe. */
val AppSettings.homeLayout: HomeLayout get() = HomeLayout(navTabs, surfaces)
