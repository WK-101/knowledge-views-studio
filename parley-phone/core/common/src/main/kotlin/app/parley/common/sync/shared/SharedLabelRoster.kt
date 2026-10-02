package app.parley.common.sync.shared

import app.parley.common.spam.Ed25519

/** A member of a shared label as the members list shows them. */
data class LabelMember(
    val keyHex: String,
    val key: ByteArray,
    val name: String,
    val fingerprint: String,
    /** The member who let them in (null for the anchor and for members carried over a key change). */
    val invitedBy: String?,
    val anchor: Boolean,
    /** Carried over the last key change and not back yet: their phone needs a new invitation. */
    val awaitingKey: Boolean = false,
) {
    override fun equals(other: Any?) = other is LabelMember && other.keyHex == keyHex && other.name == name && other.invitedBy == invitedBy &&
        other.anchor == anchor && other.awaitingKey == awaitingKey

    override fun hashCode() = keyHex.hashCode()
}

/**
 * Who is a member of a shared label (docs/SHARED_LABELS.md, "Who is a member"), worked out from the journals in the
 * folder, never from a list anyone could edit: the [anchor] (from the invitation), the members the anchor's journal
 * carried over a key change, and, repeatedly, every journal whose ticket a member signed for this label and epoch. A
 * journal marked left is not a member. Journals must already be checked ([SharedLabelFiles.readJournal]).
 *
 * An invitation is a bearer token, so a journal this phone hasn't counted before ([known]) also needs a ticket that
 * hasn't expired (with [LATE_GRACE_MS] for a phone that was off for days: [now] is this phone's clock) and that no
 * other journal used: one invitation lets in one member. When two newcomers show the same invitation, neither is let
 * in (nobody can tell which one it was meant for); a member already counted keeps their place.
 */
object SharedLabelRoster {
    /** How long after an invitation expires a phone still accepts the member it let in (a phone that was off). */
    const val LATE_GRACE_MS = 7L * 24 * 60 * 60 * 1000

    @Suppress("LongParameterList") // The label, its key's epoch and anchor, the journals, and what this phone knows.
    fun members(
        labelId: String,
        epoch: Int,
        anchor: ByteArray,
        anchorName: String,
        journals: List<Journal>,
        now: Long,
        known: Set<String> = emptySet(),
    ): List<LabelMember> {
        val current = journals.filter { it.epoch == epoch }.associateBy { it.memberHex }
        // Invitation ids shown by more than one journal: only a member already counted may keep using theirs.
        val shared = current.values.mapNotNull { it.ticket?.inviteId }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val anchorHex = SharedLabelFiles.keyHex(anchor)
        val out = LinkedHashMap<String, LabelMember>()
        val anchorJournal = current[anchorHex]
        if (anchorJournal?.left != true) {
            val name = anchorJournal?.name?.ifBlank { null } ?: anchorName
            out[anchorHex] = LabelMember(anchorHex, anchor, name, Ed25519.fingerprint(anchor), null, anchor = true)
        }
        // Carried members are vouched for by the anchor's own signed journal.
        anchorJournal?.carried.orEmpty().filter { carriedIn(it, anchorHex, current, out) }.forEach { c ->
            val hex = SharedLabelFiles.keyHex(c.key)
            val own = current[hex]
            val name = own?.name?.ifBlank { null } ?: c.name
            out[hex] = LabelMember(hex, c.key, name, Ed25519.fingerprint(c.key), null, anchor = false, awaitingKey = own == null)
        }
        // Everyone a member let in, until nothing new turns up.
        val trusted = HashSet(out.keys + anchorHex)
        var grew = true
        while (grew && out.size < SharedLabelFiles.MAX_MEMBERS) {
            val admitted = current.values.filter { j ->
                j.memberHex !in trusted && admits(j, labelId, epoch, trusted) && (j.memberHex in known || fresh(j.ticket!!, shared, now))
            }
            grew = admitted.isNotEmpty()
            admitted.forEach { j ->
                trusted += j.memberHex
                val inviter = SharedLabelFiles.keyHex(j.ticket!!.inviter)
                if (!j.left) out[j.memberHex] = LabelMember(j.memberHex, j.member, j.name, Ed25519.fingerprint(j.member), inviter, anchor = false)
            }
        }
        return out.values.toList()
    }

    private fun carriedIn(c: Carried, anchorHex: String, current: Map<String, Journal>, out: Map<String, LabelMember>): Boolean {
        val hex = SharedLabelFiles.keyHex(c.key)
        val stays = current[hex]?.left != true && hex != anchorHex
        return stays && hex !in out && out.size < SharedLabelFiles.MAX_MEMBERS
    }

    /** A newcomer's ticket: not expired (give or take [LATE_GRACE_MS]) and not shown by anyone else. */
    private fun fresh(t: Ticket, shared: Set<String>, now: Long): Boolean =
        t.inviteId !in shared && now <= t.expiresAt.let { if (it > Long.MAX_VALUE - LATE_GRACE_MS) Long.MAX_VALUE else it + LATE_GRACE_MS }

    /** Whether [j]'s ticket was signed, for this label and epoch, by someone already [trusted]. */
    private fun admits(j: Journal, labelId: String, epoch: Int, trusted: Set<String>): Boolean {
        val t = j.ticket ?: return false
        return t.epoch == epoch && SharedLabelFiles.keyHex(t.inviter) in trusted && SharedLabelFiles.verifyTicket(t, labelId)
    }
}
