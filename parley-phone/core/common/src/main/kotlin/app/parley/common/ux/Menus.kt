package app.parley.common.ux

/**
 * The ⋮ menus and action sheets that grew past the seven-item rule (README, GLOSSARY): the contact page's ⋮, the
 * Contacts selection ⋮ and a Recents call's actions. Each is built here from plain facts, most used first, with the
 * rarer actions grouped under one entry that opens a sheet of its own (Share…, Privacy…, More…, Allow, report…). The
 * screens draw exactly what these return, so a test can hold every menu to [MENU_LIMIT]. Privacy… is the selection's
 * only: a single contact's page has its "Kept as" row instead.
 */
const val MENU_LIMIT = 7

/**
 * A group's own sheet: its entry in the menu reads "Share…", "Privacy…", "More…" or, for [WHY_IT_RANG], "Allow, report…"
 * (a number's screening: why a call rang, test a call, allow it, report it).
 */
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

/**
 * The contact page's ⋮: Remind me to call and Block (or Unblock) at the top, as the everyday ones; Version history
 * and the rest of the rarer ones under More…. How the contact is kept (Visible · Private · Archived), how long
 * (Delete automatically) and its ringtone are rows of the page's own settings ("Kept as", D9), not menu items.
 */
object ContactMenu {
    enum class Action {
        REMIND_TO_CALL,
        SHARE_FILE, SHOW_QR, SHARE_ENCRYPTED_QR,
        BLOCK_NUMBERS, UNBLOCK_NUMBERS,
        LOG_CHAT_OR_VISIT, CASE_FILE, VERSION_HISTORY, ADD_TO_HOME_SCREEN, COPY_TO_SIM, ALLOW_SIMILAR_NUMBERS, SEPARATE,
        DELETE,
    }

    data class Facts(
        val canShareFile: Boolean = true,
        val canSeeVersions: Boolean = true,
        val canAddToHomeScreen: Boolean = true,
        val hasNumbers: Boolean = true,
        val canCopyToSim: Boolean = true,
        /** Linked from several raw contacts (Separate). */
        val linked: Boolean = false,
        /** In the Circle, where "Log a chat or visit" is the page's own button instead. */
        val inCircle: Boolean = false,
        /** One of the numbers is blocked now: Unblock takes Block's place. */
        val blocked: Boolean = false,
        /** Every number is an emergency number (a saved "Police"): never blocked, so neither Block nor Unblock. */
        val onlyEmergency: Boolean = false,
        /** A case file shows on the page already (kept, or an organisation's): its card opens it, so no "Keep a case file". */
        val caseShown: Boolean = false,
        /** A company rather than a person: "Keep a case file" is at the top for it, under More… for a person. */
        val isCompany: Boolean = false,
    )

    fun build(f: Facts): List<MenuEntry<Action>> = buildList {
        add(Action.REMIND_TO_CALL, f.hasNumbers)
        group(MenuGroup.SHARE, listOfNotNull(Action.SHARE_FILE.takeIf { f.canShareFile }, Action.SHOW_QR, Action.SHARE_ENCRYPTED_QR))?.let(::add)
        add(if (f.blocked) Action.UNBLOCK_NUMBERS else Action.BLOCK_NUMBERS, f.hasNumbers && !f.onlyEmergency)
        // Case files are for organisations: a company's is a tap away; anyone else's (a bank saved under a person's name,
        // a landlord) is under More….
        val caseFile = f.hasNumbers && !f.caseShown
        add(Action.CASE_FILE, caseFile && f.isCompany)
        group(
            MenuGroup.MORE,
            listOfNotNull(
                Action.LOG_CHAT_OR_VISIT.takeIf { !f.inCircle },
                Action.CASE_FILE.takeIf { caseFile && !f.isCompany },
                Action.VERSION_HISTORY.takeIf { f.canSeeVersions },
                Action.ADD_TO_HOME_SCREEN.takeIf { f.canAddToHomeScreen },
                Action.COPY_TO_SIM.takeIf { f.hasNumbers && f.canCopyToSim },
                Action.ALLOW_SIMILAR_NUMBERS.takeIf { f.hasNumbers },
                Action.SEPARATE.takeIf { f.linked },
            ),
        )?.let(::add)
        add(Action.DELETE)
    }
}

/**
 * The ⋮ of the Contacts selection bar (Select all and Star are buttons on the bar itself). Edit… opens the bulk edit
 * sheet: labels (Add to label lives there), ringtone, SIM and account for all of them at once. Share… holds every
 * way to share them, so there is one Share, not a bar button and a menu item.
 */
object SelectionMenu {
    enum class Action { EDIT, MESSAGE_ALL, SHARE_FILE, COPY_AS_TEXT, EXPORT_VCF, MERGE, DELETE_AUTOMATICALLY, MAKE_PRIVATE, MAKE_VISIBLE, ARCHIVE, DELETE }

    data class Facts(
        /** Some chosen contacts are device contacts (sharing, the clipboard, files and "Make private" are for those only). */
        val hasDevice: Boolean = true,
        /** Some are private contacts ("Make visible"). */
        val hasPrivate: Boolean = false,
        /** Two or more device contacts, which can be merged. */
        val canMerge: Boolean = false,
    )

    fun build(f: Facts): List<MenuEntry<Action>> = buildList {
        add(Action.EDIT)
        add(Action.MESSAGE_ALL)
        group(
            MenuGroup.SHARE,
            listOfNotNull(Action.SHARE_FILE.takeIf { f.hasDevice }, Action.COPY_AS_TEXT.takeIf { f.hasDevice }, Action.EXPORT_VCF.takeIf { f.hasDevice }),
        )?.let(::add)
        add(Action.MERGE, f.canMerge)
        group(
            MenuGroup.PRIVACY,
            // Archive works for both kinds: device contacts leave the address book, private ones stay in the vault.
            listOfNotNull(Action.DELETE_AUTOMATICALLY, Action.MAKE_PRIVATE.takeIf { f.hasDevice }, Action.MAKE_VISIBLE.takeIf { f.hasPrivate }, Action.ARCHIVE),
        )?.let(::add)
        add(Action.DELETE)
    }
}

/**
 * A Recents call's actions sheet, opened from the selection bar's ⋮ (a long-press selects the call). Call, Message,
 * Message or call on… and Copy are one row of buttons at the top ([quick]); the rows below come from [build]: saving
 * the number, Remind me to call and Block first, the number's screening under "Allow, report…", Edit before call and
 * Search the web under More….
 */
object RecentMenu {
    enum class Action {
        CALL, MESSAGE, MESSAGE_OR_CALL_ON, COPY_NUMBER,
        CREATE_CONTACT, ADD_TO_CONTACT, EDIT_BEFORE_CALL, REMIND_TO_CALL, BLOCK, UNBLOCK,
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
        /** The number is blocked now: Unblock takes Block's place. */
        val blocked: Boolean = false,
        /** An emergency number (112, 911, a local service): never blocked or reported, so neither is offered. */
        val emergency: Boolean = false,
        /** Some call in the row came in (rang, was missed, declined or blocked): only then is there a "why it rang". */
        val rang: Boolean = true,
    )

    /** The buttons at the top of the sheet: one row of buttons, not a menu entry. */
    fun quick(f: Facts): List<Action> =
        if (f.hasNumber) listOf(Action.CALL, Action.MESSAGE, Action.MESSAGE_OR_CALL_ON, Action.COPY_NUMBER) else emptyList()

    fun build(f: Facts): List<MenuEntry<Action>> = buildList {
        add(Action.CREATE_CONTACT, f.hasNumber && !f.saved)
        add(Action.ADD_TO_CONTACT, f.hasNumber && !f.saved)
        // Calling back later is what most people want from a call row after calling now.
        add(Action.REMIND_TO_CALL, f.hasNumber)
        add(if (f.blocked) Action.UNBLOCK else Action.BLOCK, f.hasNumber && !f.emergency)
        if (f.hasNumber) {
            group(
                MenuGroup.WHY_IT_RANG,
                listOfNotNull(
                    Action.WHY_IT_RANG.takeIf { f.rang }, Action.TEST_A_CALL, Action.SALES_LINE.takeIf { f.salesLine },
                    Action.ALWAYS_ALLOW.takeIf { !f.saved }, Action.ALLOW_24H.takeIf { !f.saved }, Action.REPORT.takeIf { !f.saved && !f.emergency },
                ),
            )?.let(::add)
            group(MenuGroup.MORE, listOf(Action.EDIT_BEFORE_CALL, Action.SEARCH_WEB))?.let(::add)
        }
        add(Action.DELETE_FROM_HISTORY)
    }
}
