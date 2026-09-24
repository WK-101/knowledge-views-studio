package app.parley.common

import app.parley.common.calltime.CallingConfig
import app.parley.common.calltime.LimitRule
import app.parley.common.calltime.LimitScope

/**
 * Labels are referred to by title everywhere (block/allow rules, off hours, call-time limits, label ringtones),
 * the way the Contacts tab shows them: one title covers the groups of the same name in every account, and a
 * title survives backups and new phones where group row ids don't.
 *
 * Older versions stored the device-local group row id; those references are read by the title saved next to
 * them and rewritten by [migrateRule], [migrateOffHours] and [migrateLimit] once the current groups are known.
 */
object LabelRefs {
    /** How titles are compared (the labels screen groups accounts' groups by trimmed title). */
    fun key(title: String): String = title.trim()

    /** A LABEL rule's title: the pattern, or for an old id-based rule the title saved in [BlockRule.label]. */
    fun ruleTitle(pattern: String, label: String?): String {
        val p = pattern.trim()
        return if (isLegacyId(p, label)) key(label!!) else p
    }

    private fun isLegacyId(key: String, title: String?): Boolean =
        title != null && title.isNotBlank() && key.toLongOrNull() != null && key != title.trim()

    fun isLegacyRule(r: BlockRule): Boolean = r.type == RuleType.LABEL && isLegacyId(r.pattern.trim(), r.label)

    /** The label title a LABEL-scope limit rule applies to (old rules kept the group id in the key). */
    fun limitTitle(rule: LimitRule): String = if (isLegacyId(rule.key.trim(), rule.title)) key(rule.title) else key(rule.key)

    fun isLegacyLimit(rule: LimitRule): Boolean = rule.scope == LimitScope.LABEL && isLegacyId(rule.key.trim(), rule.title)

    /** Rewrites an old id-based rule by title: the group's current title when it still exists, else the saved one. */
    fun migrateRule(r: BlockRule, groupTitles: Map<Long, String>): BlockRule {
        if (!isLegacyRule(r)) return r
        val t = groupTitles[r.pattern.trim().toLong()]?.let(::key) ?: key(r.label!!)
        return r.copy(pattern = t, label = t)
    }

    fun migrateOffHours(o: OffHours, groupTitles: Map<Long, String>): OffHours {
        val id = o.labelId ?: return o
        val t = groupTitles[id]?.let(::key) ?: o.labelTitle?.let(::key)
        return o.copy(labelId = null, labelTitle = t)
    }

    fun migrateLimit(rule: LimitRule, groupTitles: Map<Long, String>): LimitRule {
        if (!isLegacyLimit(rule)) return rule
        val t = groupTitles[rule.key.trim().toLong()]?.let(::key) ?: key(rule.title)
        return rule.copy(key = t, title = t)
    }

    fun migrateConfig(config: CallingConfig, groupTitles: Map<Long, String>): CallingConfig {
        if (config.rules.none { isLegacyLimit(it) }) return config
        return config.copy(rules = dedupe(config.rules.map { migrateLimit(it, groupTitles) }))
    }

    /**
     * A rule from a backup, remapped by title against the labels that exist here ([titles]). Rules for labels
     * that don't exist are dropped (null). Other rules are returned unchanged.
     */
    fun restoreRule(r: BlockRule, titles: Set<String>): BlockRule? {
        if (r.type != RuleType.LABEL) return r
        // An old backup's group id means nothing on this device: only its saved title can be trusted.
        val t = if (isLegacyRule(r)) key(r.label!!) else if (r.pattern.trim().toLongOrNull() != null && r.label == null) return null else key(r.pattern)
        return if (t.isNotEmpty() && t in titles.map(::key)) r.copy(pattern = t, label = t) else null
    }

    /** Off hours from a backup: an old id-based label is read by its title; a label missing here turns "only label" off. */
    fun restoreOffHours(o: OffHours, titles: Set<String>): OffHours {
        if (o.labelId == null && o.allow != OffHoursAllow.LABEL) return o
        val t = o.labelTitle?.let(::key)
        return if (t != null && t in titles.map(::key)) o.copy(labelId = null, labelTitle = t) else labelGone(o)
    }

    /** Off hours after its "only this label" was deleted: everyone would be silenced, so it switches itself off. */
    fun labelGone(o: OffHours): OffHours =
        if (o.allow == OffHoursAllow.LABEL) o.copy(enabled = false, allow = OffHoursAllow.CONTACTS, labelId = null, labelTitle = null) else o.copy(labelId = null)

    // ---- Rename, merge and delete (dependent references follow the label) ----

    /** [renames] maps old titles to new ones (a merge maps every source to the target). */
    fun renameRules(rules: List<BlockRule>, renames: Map<String, String>): List<BlockRule> {
        val m = renames.mapKeys { key(it.key) }.mapValues { key(it.value) }
        return rules.map { r -> r.labelKey?.let { m[it] }?.let { t -> r.copy(pattern = t, label = t) } ?: r }
    }

    fun renameOffHours(o: OffHours, renames: Map<String, String>): OffHours {
        val t = o.labelTitle?.let(::key) ?: return o
        val to = renames.entries.firstOrNull { key(it.key) == t }?.value ?: return o
        return o.copy(labelId = null, labelTitle = key(to))
    }

    fun renameConfig(config: CallingConfig, renames: Map<String, String>): CallingConfig {
        val m = renames.mapKeys { key(it.key) }.mapValues { key(it.value) }
        var changed = false
        val rules = config.rules.map { r ->
            if (r.scope != LimitScope.LABEL) return@map r
            val to = m[limitTitle(r)] ?: return@map r
            changed = true
            r.copy(key = to, title = to)
        }
        return if (changed) config.copy(rules = dedupe(rules)) else config
    }

    fun renameRingtones(tones: Map<String, String>, renames: Map<String, String>): Map<String, String> {
        val m = renames.mapKeys { key(it.key) }.mapValues { key(it.value) }
        val out = LinkedHashMap<String, String>()
        // Tones of labels that weren't renamed first, so a merge target keeps its own tone.
        tones.forEach { (t, v) -> if (key(t) !in m) out[t] = v }
        tones.forEach { (t, v) -> m[key(t)]?.let { to -> out.putIfAbsent(to, v) } }
        return out
    }

    /** The ringtone of a caller with these labels: the first label (alphabetically) that has one, or null. */
    fun ringtoneFor(titles: Set<String>, tones: Map<String, String>): String? {
        if (tones.isEmpty() || titles.isEmpty()) return null
        val byKey = tones.mapKeys { key(it.key) }
        return titles.map(::key).sorted().firstNotNullOfOrNull { byKey[it] }
    }

    /**
     * Ringtones kept on label allow rules by older versions ("Ringtone for label" created an allow rule, which
     * also let the label ring through off hours). Returns the tones to add to the label page's store (existing
     * tones win) and the ids of the rules that only existed to carry a tone and should be deleted.
     */
    fun liftRuleRingtones(rules: List<BlockRule>, tones: Map<String, String>): Pair<Map<String, String>, List<Long>> {
        val add = LinkedHashMap<String, String>()
        val drop = ArrayList<Long>()
        val have = tones.keys.map(::key).toSet()
        for (r in rules) {
            if (r.type != RuleType.LABEL || r.kind != RuleKind.ALLOW) continue
            val tone = r.ringtone ?: continue
            val t = r.labelKey ?: continue
            if (t !in have) add.putIfAbsent(t, tone)
            // Made by the label dialog: nothing but a label and a tone.
            if (r.note.isNullOrBlank() && r.simId == null && r.schedule == null && r.expiresAt == null) drop += r.id
        }
        return add to drop
    }

    /** True when [ref] (a stored title) refers to one of [titles]. */
    fun refersTo(ref: String?, titles: Set<String>): Boolean = ref != null && key(ref) in titles.map(::key)

    fun deleteFromConfig(config: CallingConfig, titles: Set<String>): CallingConfig {
        val rules = config.rules.filterNot { it.scope == LimitScope.LABEL && refersTo(limitTitle(it), titles) }
        return if (rules.size == config.rules.size) config else config.copy(rules = rules)
    }

    /** Two limit rules for the same label (after a merge): the strictest per-call limit and allowances are kept. */
    private fun dedupe(rules: List<LimitRule>): List<LimitRule> {
        val out = LinkedHashMap<String, LimitRule>()
        for (r in rules) {
            val prev = out[r.id]
            out[r.id] = if (prev == null) r else prev.copy(
                perCallMinutes = stricter(prev.perCallMinutes, r.perCallMinutes),
                dailyMinutes = stricter(prev.dailyMinutes, r.dailyMinutes),
                weeklyMinutes = stricter(prev.weeklyMinutes, r.weeklyMinutes),
                incoming = prev.incoming || r.incoming,
                outgoing = prev.outgoing || r.outgoing,
            )
        }
        return out.values.toList()
    }

    private fun stricter(a: Int, b: Int): Int = when {
        a <= 0 -> b
        b <= 0 -> a
        else -> minOf(a, b)
    }
}
