package app.parley.common.people

/**
 * The editor's temporary-contact choices, the same ones "Save temporary contact" on the keypad offers: how long
 * (1, 7 or 30 days, or a custom number of days), whether it stays private in Parley (the default) or is visible to
 * other apps, and whether its call history goes with it (the default).
 */
data class TemporaryChoice(
    val days: Int = DEFAULT_DAYS,
    val private: Boolean = true,
    val purgeHistory: Boolean = true,
) {
    fun expiresAt(now: Long): Long = now + days * DAY_MS

    companion object {
        const val DEFAULT_DAYS = 7
        const val MAX_DAYS = 3650
        const val DAY_MS = 86_400_000L
        val presets = listOf(1, 7, 30)

        /** A typed number of days, or null when it isn't one Parley accepts (1 to 3650). */
        fun parseDays(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..MAX_DAYS }
    }
}

/**
 * What saving an existing contact does to its expiry, as chosen in the editor. No change is `null` at the call site,
 * so a plain edit never touches the expiry.
 */
sealed interface ExpiryChange {
    /** Keep it permanently: the expiry goes. */
    data object Keep : ExpiryChange

    /** Delete it [days] days from the save. */
    data class After(val days: Int) : ExpiryChange

    companion object {
        /**
         * The change to apply, given the expiry the contact has now ([currentlyTemporary]) and the editor's pick
         * ([picked], null: untouched). Picking "Keep permanently" on a contact that isn't temporary changes nothing.
         */
        fun resolve(currentlyTemporary: Boolean, picked: ExpiryChange?): ExpiryChange? = when (picked) {
            null -> null
            Keep -> if (currentlyTemporary) Keep else null
            is After -> picked
        }
    }
}
