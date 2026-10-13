package app.parley.telecom.ui

import app.parley.common.catching
import app.parley.telecom.CallUi

/** The lines the call screen shows (number and SIM): every call, a conference's people and the call that just ended. */
internal fun callLines(calls: List<CallUi>, ended: CallUi?): List<Pair<String, String?>> =
    (calls + listOfNotNull(ended)).flatMap { listOf(it) + it.children }
        .mapNotNull { c -> c.number?.takeIf { it.isNotBlank() }?.let { it to c.accountId } }
        .distinct()

/**
 * Whether any of [lines] is a private contact's ([isPrivate]): the call screen then keeps out of recents' thumbnails
 * ([app.parley.common.security.WindowShield.callWindow]). A check that fails counts as private.
 */
internal suspend fun anyPrivateLine(lines: List<Pair<String, String?>>, isPrivate: suspend (String, String?) -> Boolean): Boolean =
    lines.any { (number, account) -> catching { isPrivate(number, account) }.getOrDefault(true) }
