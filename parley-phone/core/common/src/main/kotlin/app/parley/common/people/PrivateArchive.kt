package app.parley.common.people

/**
 * Archived private contacts (docs/CONTACT_MODEL.md, "Archived"): a private contact archived stays in the vault, sealed
 * and behind the private lock as before, with an "archived" mark in its caller-ID copy. It leaves Parley's lists and
 * is still named on calls under the private rules; Contacts › ⋮ › Archived and Recall show it only when [mayShow].
 */
object PrivateArchive {
    /**
     * Whether archived private contacts may be shown: private contacts aren't [hidden] (discreet mode), no duress unlock
     * is [hiding] them, and they aren't [locked] with "Lock private contacts". Any of these unknown counts as true.
     */
    fun mayShow(hidden: Boolean, hiding: Boolean, locked: Boolean): Boolean = !hidden && !hiding && !locked
}
