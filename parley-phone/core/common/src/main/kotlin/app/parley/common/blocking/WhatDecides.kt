package app.parley.common.blocking

import app.parley.common.ScreeningSettings
import app.parley.common.sync.shared.ShieldMode
import app.parley.common.ux.SalesLines

/**
 * Blocking & screening's "What decides" list (docs/BLOCKING.md): the four sources a verdict can come from, each with
 * its state, read-only. They stay separate engines, each set where it lives; this is where a person sees them side by
 * side, since where a verdict came from is what makes it trustworthy.
 */
object WhatDecides {
    enum class Source {
        /** Your own block and allow rules. */
        YOUR_RULES,

        /** The spam lists you installed. */
        SPAM_LISTS,

        /** Numbers your own calls tag as sales lines. */
        SALES_LINES,

        /** Numbers someone in a shared label warned about. */
        FAMILY_SHIELD,
    }

    /** A shared label with the shield on: its title here, and what a match does on this phone. */
    data class Shield(val label: String, val mode: ShieldMode)

    /** One row: [on] says whether it takes part now; the rest is what the row says about it. */
    data class Row(
        val source: Source,
        val on: Boolean,
        /** Your rules: block rules on; spam lists: lists installed. */
        val count: Int = 0,
        /** Your rules: allow rules. */
        val allowCount: Int = 0,
        val sales: SalesLines = SalesLines.OFF,
        val shields: List<Shield> = emptyList(),
    )

    fun rows(blockRulesOn: Int, allowRules: Int, lists: Int, settings: ScreeningSettings, shields: List<Shield>): List<Row> {
        val sales = SalesLines.of(settings.learnFromCalls, settings.silenceSalesLines)
        return listOf(
            Row(Source.YOUR_RULES, blockRulesOn > 0 || allowRules > 0, count = blockRulesOn, allowCount = allowRules),
            Row(Source.SPAM_LISTS, lists > 0, count = lists),
            Row(Source.SALES_LINES, sales != SalesLines.OFF, sales = sales),
            Row(Source.FAMILY_SHIELD, shields.isNotEmpty(), shields = shields.sortedBy { it.label.lowercase() }),
        )
    }

    /**
     * Whether unknown callers are silenced or declined at all (Contacts only, or Off hours). Repeat callers and
     * "Expecting a call" only matter then, so Blocking & screening greys them out otherwise.
     */
    fun silencesUnknown(s: ScreeningSettings): Boolean = s.blockNonContacts || s.offHours.enabled
}
