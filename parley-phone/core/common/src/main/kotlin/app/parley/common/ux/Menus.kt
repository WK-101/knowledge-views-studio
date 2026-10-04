package app.parley.common.ux

/**
 * The ⋮ menus and action sheets that grew past the seven-item rule (README, GLOSSARY): the contact page's ⋮, the
 * Contacts selection ⋮ and a Recents call's actions. Each is built here from plain facts, most used first, with the
 * rarer actions grouped under one entry that opens a sheet of its own (Share…, Privacy…, More…, Why it rang…). The
 * screens draw exactly what these return, so a test can hold every menu to [MENU_LIMIT].
 */
const val MENU_LIMIT = 7

/** A group's own sheet: its entry in the menu reads "Share…", "Privacy…", "More…" or "Why it rang…". */
enum class MenuGroup { SHARE, PRIVACY, MORE, WHY_IT_RANG }

/** One entry at the top of a menu: an action, or a [MenuGroup] holding several. */
sealed interface MenuEntry<out A> {
    data class Action<A>(val action: A) : MenuEntry<A>

    data class Group<A>(val group: MenuGroup, val actions: List<A>) : MenuEntry<A>
}

/** [actions] as one entry: nothing when empty, the action itself when alone (a sheet of one is a wasted tap). */
private fun <A> group(group: MenuGroup, actions: List<A>): MenuEntry<A>? = when (actions.size) {
    0 -> null
    1 -> MenuEntry.Action(actions.single())
    else -> MenuEntry.Group(group, actions)
}

private fun <A> MutableList<MenuEntry<A>>.add(action: A, shown: Boolean = true) {
    if (shown) add(MenuEntry.Action(action))
}

/** The contact page's ⋮. */
object ContactMenu {
    enum class Action {
        SHARE_FILE, SHOW_QR, SHARE_ENCRYPTED_QR,
        VERSION_HISTORY, BLOCK_NUMBERS,
        MAKE_PRIVATE, MAKE_VISIBLE, DELETE_AUTOMATICALLY,
        LOG_CHAT_OR_VISIT, ADD_TO_HOME_SCREEN, COPY_TO_SIM, SET_RINGTONE, ALLOW_SIMILAR_NUMBERS, SEPARATE,
        DELETE,
    }

    data class Facts(
        val isPrivate: Boolean = false,
        val canShareFile: Boolean = true,
        val canSeeVersions: Boolean = true,
        val canAddToHomeScreen: Boolean = true,
        val hasNumbers: Boolean = true,
        val canCopyToSim: Boolean = true,
        val canSetRingtone: Boolean = true,
        /** Linked from several raw contacts (Separate). */
        val linked: Boolean = false,
        /** In the Circle, where "Log a chat or visit" is the page's own button instead. */
        val inCircle: Boolean = false,
    )

    fun build(f: Facts): List<MenuEntry<Action>> = buildList {
        group(MenuGroup.SHARE, listOfNotNull(Action.SHARE_FILE.takeIf { f.canShareFile }, Action.SHOW_QR, Action.SHARE_ENCRYPTED_QR))?.let(::add)
        add(Action.VERSION_HISTORY, f.canSeeVersions)
        add(Action.BLOCK_NUMBERS, f.hasNumbers)
        group(MenuGroup.PRIVACY, listOf(if (f.isPrivate) Action.MAKE_VISIBLE else Action.MAKE_PRIVATE, Action.DELETE_AUTOMATICALLY))?.let(::add)
        group(
            MenuGroup.MORE,
            listOfNotNull(
                Action.LOG_CHAT_OR_VISIT.takeIf { !f.inCircle },
                Action.ADD_TO_HOME_SCREEN.takeIf { f.canAddToHomeScreen },
                Action.SET_RINGTONE.takeIf { f.canSetRingtone },
                Action.COPY_TO_SIM.takeIf { f.hasNumbers && f.canCopyToSim },
                Action.ALLOW_SIMILAR_NUMBERS.takeIf { f.hasNumbers },
                Action.SEPARATE.takeIf { f.linked },
            ),
        )?.let(::add)
        add(Action.DELETE)
    }
}

/** The ⋮ of the Contacts selection bar (Select all, Star and Share are buttons on the bar itself). */
object SelectionMenu {
    enum class Action { ADD_TO_LABEL, MESSAGE_ALL, COPY_AS_TEXT, EXPORT_VCF, MERGE, DELETE_AUTOMATICALLY, MAKE_PRIVATE, MAKE_VISIBLE, DELETE }

    data class Facts(
        /** Some chosen contacts are device contacts (the clipboard, files and "Make private" are for those only). */
        val hasDevice: Boolean = true,
        /** Some are private contacts ("Make visible"). */
        val hasPrivate: Boolean = false,
        /** Two or more device contacts, which can be merged. */
        val canMerge: Boolean = false,
    )

    fun build(f: Facts): List<MenuEntry<Action>> = buildList {
        add(Action.ADD_TO_LABEL)
        add(Action.MESSAGE_ALL)
        group(MenuGroup.SHARE, listOfNotNull(Action.COPY_AS_TEXT.takeIf { f.hasDevice }, Action.EXPORT_VCF.takeIf { f.hasDevice }))?.let(::add)
        add(Action.MERGE, f.canMerge)
        group(
            MenuGroup.PRIVACY,
            listOfNotNull(Action.DELETE_AUTOMATICALLY, Action.MAKE_PRIVATE.takeIf { f.hasDevice }, Action.MAKE_VISIBLE.takeIf { f.hasPrivate }),
        )?.let(::add)
        add(Action.DELETE)
    }
}

/**
 * A Recents call's actions sheet, opened from the selection bar's ⋮ (a long-press selects the call). Call, Message,
 * Message or call on… and Copy are one row of buttons at the top ([quick]); the rows below come from [build].
 */
object RecentMenu {
    enum class Action {
        CALL, MESSAGE, MESSAGE_OR_CALL_ON, COPY_NUMBER,
        CREATE_CONTACT, ADD_TO_CONTACT, EDIT_BEFORE_CALL, BLOCK,
        WHY_IT_RANG, TEST_A_CALL, SALES_LINE, ALWAYS_ALLOW, ALLOW_24H, REPORT, SEARCH_WEB,
        DELETE_FROM_HISTORY,
    }

    data class Facts(
        /** The call has a number one can act on (not hidden, not blank). */
        val hasNumber: Boolean = true,
        /** The number is a saved or private contact's. */
        val saved: Boolean = false,
        /** Your calls tagged the number as a sales line. */
        val salesLine: Boolean = false,
    )

    /** The buttons at the top of the sheet: one row, so not counted with the rows. */
    fun quick(f: Facts): List<Action> =
        if (f.hasNumber) listOf(Action.CALL, Action.MESSAGE, Action.MESSAGE_OR_CALL_ON, Action.COPY_NUMBER) else emptyList()

    fun build(f: Facts): List<MenuEntry<Action>> = buildList {
        add(Action.CREATE_CONTACT, f.hasNumber && !f.saved)
        add(Action.ADD_TO_CONTACT, f.hasNumber && !f.saved)
        add(Action.EDIT_BEFORE_CALL, f.hasNumber)
        add(Action.BLOCK, f.hasNumber)
        if (f.hasNumber) {
            group(
                MenuGroup.WHY_IT_RANG,
                listOfNotNull(
                    Action.WHY_IT_RANG, Action.TEST_A_CALL, Action.SALES_LINE.takeIf { f.salesLine },
                    Action.ALWAYS_ALLOW.takeIf { !f.saved }, Action.ALLOW_24H.takeIf { !f.saved }, Action.REPORT.takeIf { !f.saved },
                    Action.SEARCH_WEB,
                ),
            )?.let(::add)
        }
        add(Action.DELETE_FROM_HISTORY)
    }
}
