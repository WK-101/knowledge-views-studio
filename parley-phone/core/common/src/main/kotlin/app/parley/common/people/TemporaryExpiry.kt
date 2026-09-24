package app.parley.common.people

/**
 * What to do when a temporary contact expires. A temporary contact is a set of raw contacts Parley created (or the
 * user marked); only those may ever be deleted. When the person they belong to now also has other raw contacts
 * (it was merged, by the user or by Android's automatic linking), the other details stay, and so does the call
 * history, and the user is told.
 */
object TemporaryExpiry {
    data class Decision(
        /** Raw contact ids to delete. */
        val deleteRaws: Set<Long>,
        /** Whether the call history of the deleted numbers may be purged (nobody left using them). */
        val purgeHistory: Boolean,
        /** Other raw contacts remain: tell the user "X expired; the details you merged were kept". */
        val keptMerged: Boolean,
    ) {
        val nothingToDo: Boolean get() = deleteRaws.isEmpty()
    }

    /**
     * [stored]: raw contact ids recorded when the contact became temporary, or null for entries from before Parley
     * recorded them. [current]: every live raw contact of the contact(s) those raws (or, for old entries, the lookup
     * key) belong to now. [purgeRequested]: the entry asked for its call history to go too.
     */
    fun decide(stored: Set<Long>?, current: Set<Long>, purgeRequested: Boolean): Decision {
        if (current.isEmpty()) return Decision(emptySet(), false, false)
        if (stored == null) {
            // Old entry: only a contact that is still a single raw contact can safely be the one we created.
            return if (current.size == 1) Decision(current, purgeRequested, false) else Decision(emptySet(), false, true)
        }
        val mine = stored intersect current
        val others = current - stored
        return Decision(mine, purgeRequested && others.isEmpty() && mine.isNotEmpty(), others.isNotEmpty())
    }

    /** Raw ids stored as text in the database ("12,15"). */
    fun encodeIds(ids: Collection<Long>): String = ids.distinct().sorted().joinToString(",")

    fun decodeIds(s: String?): Set<Long>? = s?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet()?.takeIf { it.isNotEmpty() }

    /**
     * Temporary entries after linking contacts: when every linked contact was temporary the result stays temporary
     * (all their raws, the earliest expiry); as soon as one real contact is involved the user has chosen to keep the
     * details with it, so the entries are cleared. Returns the raw ids and expiry to keep, or null to clear.
     */
    fun afterJoin(temporaries: List<Pair<Set<Long>, Long>>, joinedContacts: Int): Pair<Set<Long>, Long>? {
        if (temporaries.isEmpty() || temporaries.size < joinedContacts) return null
        return temporaries.flatMap { it.first }.toSet() to temporaries.minOf { it.second }
    }

    /**
     * Two entries for the same person (a key change made them collide): all raws of both, the earlier expiry. The
     * result never loses a raw id either entry recorded, so nothing Parley created is forgotten (and nothing else
     * is added).
     */
    fun merge(a: Pair<String?, Long>, b: Pair<String?, Long>): Pair<String?, Long> {
        val ids = decodeIds(a.first).orEmpty() + decodeIds(b.first).orEmpty()
        return (if (ids.isEmpty()) a.first ?: b.first else encodeIds(ids)) to minOf(a.second, b.second)
    }

    /**
     * Key for a temporary contact whose lookup key wasn't readable yet right after it was saved: recorded by its raw
     * contact; the daily re-keying (which follows raw ids) gives it its real key.
     */
    fun pendingKey(rawId: Long): String = "$PENDING$rawId"

    fun isPendingKey(key: String): Boolean = key.startsWith(PENDING)

    private const val PENDING = "parley-raw:"
}
