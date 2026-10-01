package app.parley.common.backup

import app.parley.common.people.AccountKey
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Messengers
import app.parley.common.record.Mime
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** What the sync watchdog noticed; the order is the order of importance (the notification names the first). */
@Serializable
enum class WatchKind { ACCOUNT_REMOVED, ACCOUNT_EMPTIED, CONTACTS_VANISHED, NUMBERS_LOST, MASTER_SYNC_OFF, SYNC_OFF }

/**
 * One unexplained loss, said once. [keys] are the snapshot keys of the contacts that vanished (or lost numbers), so
 * "Restore from snapshot" can preselect them; [count] of the account's [total] at [since] (the baseline snapshot).
 */
@Serializable
data class WatchEvent(
    val kind: WatchKind,
    val accountType: String? = null,
    val accountName: String? = null,
    val keys: List<String> = emptyList(),
    val count: Int = 0,
    val total: Int = 0,
    val since: Long = 0,
    val at: Long = 0,
) {
    val account: AccountKey? get() = if (accountType == null && accountName == null) null else AccountKey(accountType, accountName)

    /** Contacts can be brought back from the snapshots (sync events only point to the settings). */
    val restorable: Boolean get() = keys.isNotEmpty()

    /**
     * Same event, same fingerprint: a loss by its account and the exact contacts, a switch by its account and the
     * baseline it was seen against. Remembered so nothing is said twice.
     */
    val fingerprint: String
        get() {
            val what = if (keys.isNotEmpty()) SyncWatchdog.digest(keys.sorted().joinToString("\n")) else since.toString()
            return listOf(kind.name, accountType.orEmpty(), accountName.orEmpty(), what).joinToString("|")
        }
}

/** The accounts as the health check sees them at one moment (see AccountDiagnostics). */
data class WatchAccounts(
    val signedIn: Set<AccountKey>,
    /** Contacts each account holds (accounts no longer signed in included). */
    val counts: Map<AccountKey, Int>,
    val syncOff: Set<AccountKey>,
    val masterSyncOn: Boolean,
    /** False when Android didn't let Parley read sync settings: no switch is reported then. */
    val syncKnown: Boolean = true,
)

/**
 * The sync watchdog (I13): from the daily snapshot diff and the accounts, tells a large unexplained loss apart from
 * the user's own changes. Losses Parley made itself (journaled deletes, edits and merges) and contacts that only
 * changed key (re-joined, re-synced) never count; small losses never count either, so it only speaks up when it
 * matters: "142 contacts vanished from Google since yesterday", an account that emptied or was removed, sync turned
 * off, or many contacts losing their numbers.
 */
object SyncWatchdog {
    /** A loss counts when at least this many contacts of one account vanished… */
    const val MIN_CONTACTS = 10

    /** …and they were at least this share of the account (percent). */
    const val MIN_PERCENT = 5

    /** A whole account emptying counts from this many contacts (a 2-contact account emptying is not news). */
    const val MIN_WHOLE_ACCOUNT = 3

    data class Input(
        /** The baseline snapshot's time. */
        val since: Long,
        val now: Long,
        val diff: SnapshotDiff,
        /** Contact keys Parley journaled since the baseline (the user's own deletes, edits and merges). */
        val userKeys: Set<String> = emptySet(),
        /** Keys already reported or dismissed ("It was me"): never reported again. */
        val acknowledged: Set<String> = emptySet(),
        /** The accounts at the baseline; null on the first run (no switch can be told from it). */
        val before: WatchAccounts?,
        val after: WatchAccounts,
    )

    /** Every event worth saying, most important first. */
    fun check(input: Input): List<WatchEvent> {
        val gone = vanished(input.diff, input.userKeys, input.acknowledged).groupBy { accountOf(it) }
        val removed = input.before?.signedIn.orEmpty().filter { it.type != null && it !in input.after.signedIn }.toSet()
        return (removedAccounts(input, gone, removed) + losses(input, gone, removed) + numbers(input) + switches(input))
            .sortedBy { it.kind.ordinal }
    }

    private fun Input.event(kind: WatchKind, a: AccountKey?, keys: List<String>, total: Int) =
        WatchEvent(kind, a?.type, a?.name, keys.sorted(), keys.size, total, since, now)

    /** Accounts signed in at the baseline and gone now; one that held no contacts is not a contacts matter. */
    private fun removedAccounts(input: Input, gone: Map<AccountKey?, List<ContactRecord>>, removed: Set<AccountKey>): List<WatchEvent> =
        removed.mapNotNull { a ->
            val keys = gone[a].orEmpty().map { it.key }
            val held = maxOf(input.before?.counts?.get(a) ?: 0, keys.size)
            if (held > 0) input.event(WatchKind.ACCOUNT_REMOVED, a, keys, held) else null
        }

    /** A whole account emptied, or a large share of it vanished. */
    private fun losses(input: Input, gone: Map<AccountKey?, List<ContactRecord>>, removed: Set<AccountKey>): List<WatchEvent> =
        gone.mapNotNull { (a, list) ->
            if (a == null || a in removed) return@mapNotNull null
            val n = list.size
            val now = input.after.counts[a] ?: 0
            val total = maxOf(input.before?.counts?.get(a) ?: (now + n), n)
            when {
                now == 0 && n >= MIN_WHOLE_ACCOUNT -> input.event(WatchKind.ACCOUNT_EMPTIED, a, list.map { it.key }, total)
                significant(n, total) -> input.event(WatchKind.CONTACTS_VANISHED, a, list.map { it.key }, total)
                else -> null
            }
        }

    private fun numbers(input: Input): List<WatchEvent> {
        val lost = numberLosses(input.diff, input.userKeys, input.acknowledged)
        val everyone = input.after.counts.values.sum().coerceAtLeast(lost.size)
        return if (significant(lost.size, everyone)) listOf(input.event(WatchKind.NUMBERS_LOST, null, lost.map { it.key }, everyone)) else emptyList()
    }

    /** Sync switches that just went off (known at both ends), for accounts holding contacts (the health check lists the rest). */
    private fun switches(input: Input): List<WatchEvent> {
        val before = input.before ?: return emptyList()
        val after = input.after
        if (!before.syncKnown || !after.syncKnown) return emptyList()
        val master = if (before.masterSyncOn && !after.masterSyncOn && after.signedIn.isNotEmpty()) {
            listOf(input.event(WatchKind.MASTER_SYNC_OFF, null, emptyList(), 0))
        } else {
            emptyList()
        }
        return master + (after.syncOff - before.syncOff).filter { it in after.signedIn && (after.counts[it] ?: 0) > 0 }
            .sortedBy { "${it.type}|${it.name}" }
            .map { input.event(WatchKind.SYNC_OFF, it, emptyList(), after.counts[it] ?: 0) }
    }

    /** [n] of [total] is a loss worth saying: at least [MIN_CONTACTS] and at least [MIN_PERCENT] percent. */
    fun significant(n: Int, total: Int): Boolean = n >= MIN_CONTACTS && n * 100 >= MIN_PERCENT * total.coerceAtLeast(1)

    /** The account a contact lives in: its first raw contact outside messenger apps (null: messengers only). */
    fun accountOf(r: ContactRecord): AccountKey? =
        r.raws.firstOrNull { !Messengers.isMessengerAccount(it.accountType) }?.let { AccountKey(it.accountType, it.accountName) }

    /**
     * Contacts that are gone without an explanation: not deleted, edited or merged in Parley ([userKeys]), not already
     * said ([acknowledged]), not messenger-only, and not simply back under a new key (every raw contact of theirs
     * still exists in an added or changed contact, by sync source id or by account, name and numbers).
     */
    fun vanished(diff: SnapshotDiff, userKeys: Set<String> = emptySet(), acknowledged: Set<String> = emptySet()): List<ContactRecord> {
        val alive = HashSet<String>()
        (diff.added + diff.changed.map { it.after }).forEach { r -> r.raws.forEach { alive += identities(it) } }
        return diff.removed.filter { r ->
            r.key !in userKeys && r.key !in acknowledged && accountOf(r) != null &&
                !r.raws.filter { !Messengers.isMessengerAccount(it.accountType) }.all { raw -> identities(raw).any { it in alive } }
        }
    }

    /**
     * Of the vanished [records], those still gone from the address book as it is [now] (read just before a restore):
     * a contact that came back (sync recovered, often under a new lookup key) is never written twice. One is back when
     * its lookup key is there again, one of its raw contacts is, or every raw contact of its is recognised (by sync
     * source id, or by account, name and numbers), or another contact has its very name and numbers in any account.
     */
    fun stillGone(records: List<ContactRecord>, now: List<ContactRecord>): List<ContactRecord> {
        val keys = now.mapTo(HashSet()) { it.key }
        val rawIds = now.flatMapTo(HashSet()) { r -> r.raws.mapNotNull { it.rawId } }
        val alive = HashSet<String>()
        now.forEach { r -> r.raws.forEach { alive += identities(it) } }
        val people = now.mapNotNullTo(HashSet()) { person(it) }
        return records.filter { r ->
            val raws = r.raws.filter { !Messengers.isMessengerAccount(it.accountType) }
            val back = r.key in keys ||
                r.raws.any { it.rawId != null && it.rawId in rawIds } ||
                (raws.isNotEmpty() && raws.all { raw -> identities(raw).any { it in alive } }) ||
                person(r)?.let { it in people } == true
            !back
        }
    }

    /**
     * A contact's name and numbers, whatever the account and however the numbers are written ("ana lima|612345678", the
     * last 9 digits of each, so +44 7700 900004 and 07700 900004 agree); null without a name or a number.
     */
    private fun person(r: ContactRecord): String? {
        val name = r.displayName.trim().lowercase().takeIf { it.isNotEmpty() } ?: return null
        val numbers = phones(r).map { digits(it["data1"]).takeLast(9) }.filter { it.isNotEmpty() }.distinct().sorted()
        if (numbers.isEmpty()) return null
        return name + "|" + numbers.joinToString(",")
    }

    /**
     * Contacts that lost numbers without gaining any (a reformatted number is a change, not a loss) and that Parley
     * didn't edit.
     */
    fun numberLosses(diff: SnapshotDiff, userKeys: Set<String> = emptySet(), acknowledged: Set<String> = emptySet()): List<ContactChange> =
        diff.changed.filter { c ->
            c.key !in userKeys && c.key !in acknowledged &&
                c.removedRows.any { it.mimeType == Mime.PHONE } && c.addedRows.none { it.mimeType == Mime.PHONE } &&
                lostNumbers(c.before, c.after).isNotEmpty()
        }

    /** Phone rows of [before] whose number [current] no longer has (compared by digits), each number once. */
    fun lostNumbers(before: ContactRecord, current: ContactRecord): List<DataRow> {
        val have = phones(current).map { digits(it["data1"]) }.toHashSet()
        val seen = HashSet<String>()
        return phones(before).filter { row ->
            val d = digits(row["data1"])
            d.isNotEmpty() && d !in have && seen.add(d)
        }
    }

    private fun phones(r: ContactRecord) =
        r.raws.filter { !Messengers.isMessengerAccount(it.accountType) }.flatMap { it.rows }.filter { it.mimeType == Mime.PHONE }

    private fun digits(s: String?) = s.orEmpty().filter { it.isDigit() }

    /** Ways to recognise one raw contact after its contact changed key. */
    private fun identities(raw: app.parley.common.record.RawRecord): List<String> {
        val account = "${raw.accountType}|${raw.accountName}|${raw.dataSet}"
        val name = raw.rows.firstOrNull { it.mimeType == Mime.NAME }?.get("data1").orEmpty().trim().lowercase()
        val numbers = raw.rows.filter { it.mimeType == Mime.PHONE }.map { digits(it["data1"]) }.sorted().joinToString(",")
        return listOfNotNull(
            raw.sourceId?.takeIf { it.isNotBlank() }?.let { "s|$account|$it" },
            "c|$account|$name|$numbers".takeIf { name.isNotEmpty() || numbers.isNotEmpty() },
        )
    }

    /** Which acknowledged keys to keep: those said within [keepMs] of [now] (the snapshots don't go back further). */
    fun pruneAcknowledged(acknowledged: Map<String, Long>, now: Long, keepMs: Long): Map<String, Long> =
        acknowledged.filterValues { now - it <= keepMs }

    /** Events not said before: new fingerprints only, and each one once even if found twice in one run. */
    fun fresh(events: List<WatchEvent>, said: Set<String>): List<WatchEvent> {
        val seen = HashSet(said)
        return events.filter { seen.add(it.fingerprint) }
    }

    internal fun digest(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
}
