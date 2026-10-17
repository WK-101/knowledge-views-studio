package app.parley.data.vault

import app.parley.common.people.NameOrder
import app.parley.common.people.PrivateLabels
import app.parley.common.people.TemporaryRule
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.EventItem
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

    /** "They never call me" ([app.parley.common.extras.CallerChoice.neverCalls]). */
    const val C_NEVER_CALLS = "nc"

    /** "Always hide my number when I call them" ([app.parley.common.extras.CallerChoice.hideCallerId]). */
    const val C_HIDE_CLIR = "hc"

    /**
     * The choices set from the contact's page only (the editor doesn't show them): the vibration, auto-answer,
     * "They never call me" and "Always hide my number" of [s], written into [o] (an option that is off isn't stored).
     */
    fun putPageChoices(o: JSONObject, s: VaultSummary) {
        if (s.vibration.isNullOrBlank()) o.remove(C_VIBRATION) else o.put(C_VIBRATION, s.vibration)
        if (s.autoAnswer) o.put(C_AUTO_ANSWER, true) else o.remove(C_AUTO_ANSWER)
        if (s.neverCalls) o.put(C_NEVER_CALLS, true) else o.remove(C_NEVER_CALLS)
        if (s.hideCallerId) o.put(C_HIDE_CLIR, true) else o.remove(C_HIDE_CLIR)
    }
    const val C_PRONOUNS = "pn"

    /** The name in their own language, shown under the name on the call screen like the name itself. */
    const val C_NATIVE_NAME = "nn"
    const val C_NAME_ALT = "alt"

    /** Marks a caller-ID copy that keeps the star, labels, ringtone and voicemail itself. */
    const val C_SEEDED = "cs"

    /** When the private contact was archived (out of Parley's lists, still private and still named on calls). */
    const val C_ARCHIVED = "arch"

    /**
     * Its dates (birthday, anniversary…: date, type, label, calendar), so a birthday today reaches Next up and the call
     * screen without unlocking, like the name. Shown only while private contacts may show (docs/SECURITY_MODEL.md).
     * Present, even empty, on every copy saved since it was kept; a copy without it is seeded ([VaultRepository]).
     */
    const val C_DATES = "dt"

    /** A temporary private contact's rule ([TemporaryRule]): days after the last call, or "until its case is closed". */
    const val C_SLIDE = "slide"
    const val C_UNTIL_CASE = "case"

    /** Writes [rule]; a fixed date (or none) leaves neither key. */
    fun putRule(o: JSONObject, rule: TemporaryRule) {
        if (rule.slideDays != null) o.put(C_SLIDE, rule.slideDays) else o.remove(C_SLIDE)
        if (rule.untilCase) o.put(C_UNTIL_CASE, true) else o.remove(C_UNTIL_CASE)
    }

    /** Sets the call-history choice and the rule where given (null: unchanged); true when the copy changed. */
    fun putExpiryChoices(o: JSONObject, purge: Boolean?, rule: TemporaryRule?): Boolean {
        val before = o.toString()
        if (purge != null) if (purge) o.put("purge", true) else o.remove("purge")
        rule?.let { putRule(o, it) }
        return o.toString() != before
    }

    /** The copy's rule, read leniently: anything unreadable is a fixed date. */
    fun ruleOf(o: JSONObject): TemporaryRule =
        TemporaryRule.stored(o.optInt(C_SLIDE, 0).takeIf { it > 0 }, o.optBoolean(C_UNTIL_CASE, false))

    /** Writes [events] (the blank ones left out) as the copy's dates. */
    fun putDates(o: JSONObject, events: List<EventItem>) {
        o.put(C_DATES, JSONArray(events.filter { it.date.isNotBlank() }.map { e ->
            JSONObject().put("d", e.date).put("t", e.type).apply {
                e.label?.takeIf { it.isNotBlank() }?.let { put("l", it) }
                e.calendar?.let { put("c", it) }
            }
        }))
    }

    /** The copy's dates; empty when it keeps none (or none yet: [hasDates]). */
    fun datesOf(o: JSONObject): List<EventItem> {
        val a = o.optJSONArray(C_DATES) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let {
                val label = it.optString("l").ifEmpty { null }
                EventItem(date = it.optString("d"), type = it.optInt("t", 3), label = label, calendar = it.optString("c").ifEmpty { null })
            }
        }.filter { it.date.isNotBlank() }
    }

    /** Whether the copy keeps the dates (saved or seeded since they were kept there). */
    fun hasDates(o: JSONObject): Boolean = o.has(C_DATES)

    /**
     * What a list row needs of the copy ([summary]), and nothing of the caller card (the note for calls, the "who is
     * this" line, title, pronouns): all a kept listing ([PrivateSummaryCache]) holds.
     */
    private val SUMMARY_KEYS = listOf(
        "name", "numbers", "u", "purge", C_STAR, C_LABELS, C_TONE, C_VOICEMAIL, C_VIBRATION, C_AUTO_ANSWER, C_NEVER_CALLS, C_HIDE_CLIR, C_SEEDED,
        C_NAME_ALT, C_REGION, C_COMPANY, C_ARCHIVED, C_DATES, C_SLIDE, C_UNTIL_CASE,
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
            neverCalls = o.optBoolean(C_NEVER_CALLS, false),
            hideCallerId = o.optBoolean(C_HIDE_CLIR, false),
            choicesKnown = o.has(C_SEEDED),
            nameAlt = alternativeOf(o),
            region = o.optString(C_REGION).ifEmpty { null },
            company = o.optString(C_COMPANY),
            createdAt = createdAt,
            archivedAt = o.optLong(C_ARCHIVED, 0L).takeIf { it > 0 },
            dates = datesOf(o),
            rule = ruleOf(o),
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
     * The details that survive a lost detail key, from the caller-ID copy [o]: name, numbers and labels, the caller
     * card's job title and company, "who is this" line and note for calls, and the dates, so re-sealing loses none.
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
            events = datesOf(o),
        )
    }
}
