package app.parley.common.extras

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * X3: what a label (by title) carries besides its ringtone. [simId]: the SIM its members are called on when they
 * have no SIM of their own; [rhythmDays]: the keep-in-touch gap offered when a member joins the Circle;
 * [allowThroughDnd]: members are starred so Android's "starred contacts" Do Not Disturb exception lets them ring
 * (which contacts Parley starred, and for which labels, is kept apart in [DndStars]).
 */
@Serializable
data class LabelPolicy(
    val simId: String? = null,
    val rhythmDays: Int? = null,
    val allowThroughDnd: Boolean = false,
) {
    val isEmpty: Boolean get() = simId == null && rhythmDays == null && !allowThroughDnd
}

object LabelPolicies {
    val RHYTHM_CHOICES = listOf(7, 14, 30, 90, 180)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = MapSerializer(String.serializer(), LabelPolicy.serializer())

    fun decode(text: String?): Map<String, LabelPolicy> = if (text.isNullOrBlank()) {
        emptyMap()
    } else {
        runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyMap()).filterValues { !it.isEmpty }
    }

    fun encode(map: Map<String, LabelPolicy>): String = json.encodeToString(serializer, map.filterValues { !it.isEmpty })

    /**
     * The SIM for someone with [labels]: the first label (alphabetically, like label ringtones) that has one whose
     * SIM is still in the phone ([available]; null = don't check). Null = no label SIM.
     */
    fun simFor(labels: Set<String>, policies: Map<String, LabelPolicy>, available: Set<String>? = null): String? =
        labels.sortedBy { it.lowercase() }.firstNotNullOfOrNull { l -> policies[l]?.simId?.takeIf { available == null || it in available } }

    /** The rhythm to offer for someone with [labels]: the closest (shortest) gap any of their labels asks for. */
    fun rhythmFor(labels: Set<String>, policies: Map<String, LabelPolicy>): Pair<String, Int>? =
        labels.mapNotNull { l -> policies[l]?.rhythmDays?.let { l to it } }.minWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })

    /** Labels were renamed or merged (old title → new title); a merge keeps the target's own policy. */
    fun renamed(map: Map<String, LabelPolicy>, renames: Map<String, String>): Map<String, LabelPolicy> {
        val out = LinkedHashMap<String, LabelPolicy>()
        map.forEach { (k, v) -> if (k !in renames) out[k] = v }
        map.forEach { (k, v) -> renames[k]?.let { to -> if (to !in out) out[to] = v } }
        return out
    }

    fun deleted(map: Map<String, LabelPolicy>, titles: Set<String>): Map<String, LabelPolicy> = map.filterKeys { it !in titles }
}

/**
 * X3: which labels made Parley star each contact for "Allow through Do Not Disturb" (lookup key → label titles), a
 * reference count kept apart from the policies so it survives a label being deleted, merged or switched off, and
 * travels in the backup. Parley unstars a contact only when it did the starring and no label that still lets people
 * through asks for it any more; a star the user set is never recorded, so never taken away.
 */
object DndStars {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), kotlinx.serialization.builtins.SetSerializer(String.serializer()))

    /** A new ledger and the contacts no label asks for any more (to unstar). */
    data class Release(val ledger: Map<String, Set<String>>, val unstar: Set<String>)

    fun decode(text: String?): Map<String, Set<String>> = if (text.isNullOrBlank()) {
        emptyMap()
    } else {
        runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyMap()).filter { (k, v) -> k.isNotEmpty() && v.isNotEmpty() }
    }

    fun encode(ledger: Map<String, Set<String>>): String = json.encodeToString(serializer, ledger.filterValues { it.isNotEmpty() })

    /** [label] asks for the contacts [keys] (Parley starred them, or they were already starred by Parley for another label). */
    fun add(ledger: Map<String, Set<String>>, label: String, keys: Collection<String>): Map<String, Set<String>> {
        val out = ledger.toMutableMap()
        keys.filter { it.isNotEmpty() }.forEach { k -> out[k] = out[k].orEmpty() + label }
        return out
    }

    /** [labels] stop asking (switched off, or deleted): their references go, and contacts left with none are released. */
    fun release(ledger: Map<String, Set<String>>, labels: Set<String>): Release {
        val out = LinkedHashMap<String, Set<String>>()
        val unstar = LinkedHashSet<String>()
        ledger.forEach { (k, v) ->
            val left = v - labels
            if (left.isNotEmpty()) out[k] = left else if (v.isNotEmpty()) unstar += k
        }
        return Release(out, unstar)
    }

    /**
     * Labels were renamed or merged (old title → new title): references follow the new title while it still lets
     * people through ([dndAfter], the titles whose policy allows it afterwards); otherwise they are released, as if
     * the policy were switched off.
     */
    fun renamed(ledger: Map<String, Set<String>>, renames: Map<String, String>, dndAfter: Set<String>): Release {
        val moved = ledger.mapValues { (_, v) -> v.map { renames[it] ?: it }.toSet() }
        val gone = moved.values.flatten().filter { it !in dndAfter }.toSet()
        return release(moved, gone)
    }

    /**
     * Before unstarring [released] contacts: those still in a label that lets people through ([wants]: key → such
     * labels they're in now) stay starred and are recorded under those labels instead.
     */
    fun settle(ledger: Map<String, Set<String>>, released: Set<String>, wants: Map<String, Set<String>>): Release {
        var out = ledger
        val unstar = LinkedHashSet<String>()
        for (k in released) {
            val still = wants[k].orEmpty()
            if (still.isEmpty()) unstar += k else still.forEach { l -> out = add(out, l, listOf(k)) }
        }
        return Release(out, unstar)
    }

    /** A contact's lookup key changed (link, unlink, account move): its references move with it. */
    fun rekey(ledger: Map<String, Set<String>>, from: String, to: String): Map<String, Set<String>> {
        if (from == to) return ledger
        val v = ledger[from] ?: return ledger
        return (ledger - from) + (to to (ledger[to].orEmpty() + v))
    }

    /** A restored ledger joins the one here. */
    fun merge(a: Map<String, Set<String>>, b: Map<String, Set<String>>): Map<String, Set<String>> =
        (a.keys + b.keys).associateWith { a[it].orEmpty() + b[it].orEmpty() }.filterValues { it.isNotEmpty() }
}
