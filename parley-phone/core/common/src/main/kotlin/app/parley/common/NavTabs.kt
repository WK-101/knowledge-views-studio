package app.parley.common

/**
 * Order and visibility of the home tabs (bottom navigation bar and navigation rail).
 *
 * Rules, enforced by every operation and by [decode]:
 * - [order] always holds every [StartTab] exactly once (unknown or missing tabs are repaired);
 * - at least one tab stays visible: hiding the last visible tab is refused.
 *
 * A tab hidden from the bar still works: deep links (ACTION_DIAL opens the Keypad, the missed-call notification
 * opens Recents…) show it anyway, and the bar shows it as an extra item until you switch to another tab
 * ([barTabs]).
 */
data class NavTabs(
    val order: List<StartTab> = DEFAULT_ORDER,
    val hidden: Set<StartTab> = HIDDEN_BY_DEFAULT,
) {
    /** Tabs shown in the bar, in order. Never empty. */
    val visible: List<StartTab> get() = order.filter { it !in hidden }.ifEmpty { listOf(order.first()) }

    fun isVisible(tab: StartTab): Boolean = tab in visible

    /** Whether [tab] can be hidden now (it isn't the last visible one). */
    fun canHide(tab: StartTab): Boolean = tab !in hidden && visible.size > 1

    fun setVisible(tab: StartTab, show: Boolean): NavTabs = when {
        show -> copy(hidden = hidden - tab)
        canHide(tab) -> copy(hidden = hidden + tab)
        else -> this
    }

    /** Moves [tab] to [to] (clamped). */
    fun move(tab: StartTab, to: Int): NavTabs {
        val from = order.indexOf(tab)
        if (from < 0) return this
        val target = to.coerceIn(0, order.size - 1)
        if (target == from) return this
        return copy(order = order.toMutableList().apply { add(target, removeAt(from)) })
    }

    fun moveUp(tab: StartTab): NavTabs = move(tab, order.indexOf(tab) - 1)
    fun moveDown(tab: StartTab): NavTabs = move(tab, order.indexOf(tab) + 1)

    /** The tab to open on: [preferred] when visible, else the first visible tab. */
    fun startTab(preferred: StartTab): StartTab = if (isVisible(preferred)) preferred else visible.first()

    /** Items for the bar while [current] is open: the visible tabs, plus [current] in its place if it's hidden. */
    fun barTabs(current: StartTab): List<StartTab> = order.filter { it in visible || it == current }

    /** "RECENTS,-FAVORITES,CONTACTS,KEYPAD": order, with hidden tabs prefixed by "-". */
    fun encode(): String = order.joinToString(",") { (if (it in hidden) "-" else "") + it.name }

    companion object {
        val DEFAULT_ORDER = listOf(StartTab.FAVORITES, StartTab.RECENTS, StartTab.CONTACTS, StartTab.KEYPAD, StartTab.CIRCLE)

        /** Optional tabs, off until shown in Settings › Navigation bar (R1: the Circle). */
        val HIDDEN_BY_DEFAULT: Set<StartTab> = setOf(StartTab.CIRCLE)

        /**
         * Reads [encode]'s format; anything unreadable falls back to the default. U6 (layout promise): a tab missing
         * from a saved value is one an update added, so it is appended *hidden*: an update never changes the bar.
         */
        fun decode(value: String?): NavTabs {
            if (value.isNullOrBlank()) return NavTabs()
            val order = ArrayList<StartTab>()
            val hidden = HashSet<StartTab>()
            value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
                val off = token.startsWith("-")
                val tab = StartTab.entries.firstOrNull { it.name == token.removePrefix("-") } ?: return@forEach
                if (tab in order) return@forEach
                order += tab
                if (off) hidden += tab
            }
            StartTab.entries.forEach {
                if (it !in order) {
                    order += it
                    hidden += it
                }
            }
            // Never hide everything: the first tab comes back.
            if (hidden.containsAll(order)) hidden -= order.first()
            return NavTabs(order, hidden)
        }
    }
}
