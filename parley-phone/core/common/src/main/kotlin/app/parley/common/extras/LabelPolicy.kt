package app.parley.common.extras

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * X3: what a label (by title) carries besides its ringtone. [simId]: the SIM its members are called on when they
 * have no SIM of their own; [rhythmDays]: the keep-in-touch gap offered when a member joins the Circle;
 * [allowThroughDnd]: members are starred so Android's "starred contacts" Do Not Disturb exception lets them ring.
 */
@Serializable
data class LabelPolicy(
    val simId: String? = null,
    val rhythmDays: Int? = null,
    val allowThroughDnd: Boolean = false,
    /** Lookup keys Parley starred for [allowThroughDnd], so turning it off unstars only those. */
    val starredByPolicy: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = simId == null && rhythmDays == null && !allowThroughDnd && starredByPolicy.isEmpty()
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
