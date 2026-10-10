package app.parley.data.vault

import app.parley.common.people.NameOrder
import app.parley.common.people.PrivateLabels
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * The caller-ID copy of a private contact: the small JSON sealed with the caller-ID key, readable without unlocking,
 * that names a caller and lists the vault. [VaultRepository] seals and opens it; this reads and writes its fields.
 */
internal object CallerIdCopy {
    const val C_TITLE = "t"
    const val C_COMPANY = "co"
    const val C_REGION = "rg"
    const val C_STAR = "star"
    const val C_LABELS = "lb"
    const val C_TONE = "rt"
    const val C_VOICEMAIL = "vm"
    const val C_VIBRATION = "vb"
    const val C_AUTO_ANSWER = "aa"
    const val C_PRONOUNS = "pn"

    /** The name in their own language, shown under the name on the call screen like the name itself. */
    const val C_NATIVE_NAME = "nn"
    const val C_NAME_ALT = "alt"

    /** Marks a caller-ID copy that keeps the star, labels, ringtone and voicemail itself. */
    const val C_SEEDED = "cs"

    /** When the private contact was archived (out of Parley's lists, still private and still named on calls). */
    const val C_ARCHIVED = "arch"

    /**
     * What a list row needs of the copy ([summary]), and nothing of the caller card (the note for calls, the "who is
     * this" line, title, pronouns): all a kept listing ([PrivateSummaryCache]) holds.
     */
    private val SUMMARY_KEYS = listOf(
        "name", "numbers", "u", "purge", C_STAR, C_LABELS, C_TONE, C_VOICEMAIL, C_VIBRATION, C_AUTO_ANSWER, C_SEEDED, C_NAME_ALT,
        C_REGION, C_COMPANY, C_ARCHIVED,
    )

    /** Copy [o] cut down to what [summary] reads. */
    fun summaryPart(o: JSONObject): JSONObject = JSONObject().also { out -> SUMMARY_KEYS.forEach { k -> if (o.has(k)) out.put(k, o.get(k)) } }

    /** The list row of entry [id] from its opened copy [o]. */
    fun summary(id: Long, o: JSONObject, expiresAt: Long?, createdAt: Long): VaultSummary {
        val nums = o.optJSONArray("numbers") ?: JSONArray()
        return VaultSummary(
            id, o.optString("name"), (0 until nums.length()).map { nums.getString(it) }, expiresAt,
            updatedAt = o.optLong("u", createdAt), purgeHistory = o.optBoolean("purge", false),
            starred = o.optBoolean(C_STAR, false), labels = labelsOf(o),
            ringtone = o.optString(C_TONE).ifEmpty { null }, sendToVoicemail = o.optBoolean(C_VOICEMAIL, false),
            vibration = o.optString(C_VIBRATION).ifEmpty { null }, autoAnswer = o.optBoolean(C_AUTO_ANSWER, false),
            choicesKnown = o.has(C_SEEDED),
            nameAlt = alternativeOf(o),
            region = o.optString(C_REGION).ifEmpty { null },
            company = o.optString(C_COMPANY),
            createdAt = createdAt,
            archivedAt = o.optLong(C_ARCHIVED, 0L).takeIf { it > 0 },
        )
    }

    /**
     * The "Family, Given" form kept in the caller-ID copy. Entries saved before it was kept have the whole name only:
     * it is guessed from that (not for a company name, which has no family name).
     */
    fun alternativeOf(o: JSONObject): String {
        val name = o.optString("name")
        o.optString(C_NAME_ALT).takeIf { it.isNotBlank() }?.let { return it }
        return if (name == o.optString(C_COMPANY)) name else NameOrder.guessAlternative(name)
    }

    fun labelsOf(o: JSONObject): List<PrivateLabels.Membership> {
        val a = o.optJSONArray(C_LABELS) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { PrivateLabels.Membership(it.optLong("i"), it.optString("t")) } }
            .filter { it.title.isNotBlank() }
    }

    fun putLabels(o: JSONObject, labels: List<PrivateLabels.Membership>) {
        if (labels.isEmpty()) o.remove(C_LABELS) else o.put(C_LABELS, JSONArray(labels.map { JSONObject().put("i", it.groupId).put("t", it.title) }))
    }

    /**
     * The details that survive a lost detail key, from the caller-ID copy [o]: name, numbers and labels, and the
     * caller card's job title and company, "who is this" line and note for calls, so re-sealing loses none of them.
     */
    fun rebuilt(id: Long, o: JSONObject): ContactDetails {
        val nums = o.optJSONArray("numbers") ?: JSONArray()
        val labels = o.optJSONArray("labels") ?: JSONArray()
        val title = o.optString(C_TITLE)
        val company = o.optString(C_COMPANY)
        // Entries saved before title and company were kept apart only have the combined line: keep it as the title.
        val fallbackTitle = if (title.isEmpty() && company.isEmpty()) o.optString("sub") else title
        return ContactDetails(
            id = -id, lookupKey = "", displayName = o.optString("name"), given = o.optString("name"),
            phones = (0 until nums.length()).map { i -> DataItem(0, nums.getString(i), labels.optInt(i, 2), null) },
            title = fallbackTitle, company = company,
            context = o.optString("ctx"), pinnedNote = o.optString("note"), pronouns = o.optString(C_PRONOUNS),
        )
    }
}
