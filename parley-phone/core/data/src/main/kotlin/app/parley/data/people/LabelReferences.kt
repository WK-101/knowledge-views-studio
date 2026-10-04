package app.parley.data.people

import app.parley.common.BlockRule
import app.parley.common.LabelRefs
import app.parley.common.OffHours
import app.parley.common.calls.SafeWord
import app.parley.common.calltime.LimitRule
import app.parley.common.extras.LabelPolicy
import app.parley.common.OffHoursAllow
import app.parley.common.RuleType
import app.parley.data.DataContainer
import app.parley.data.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps everything that names a label (block/allow rules, off hours, call-time limits, label ringtones) pointing
 * at a label that exists, by title (see [LabelRefs]):
 * - [migrate] rewrites references saved by older versions (group row ids) and moves label ringtones that were
 *   kept on allow rules to the label page's store;
 * - [renamed] and [deleted] follow a rename, merge or delete made on the labels screen (Situations too).
 */
class LabelReferences(private val c: DataContainer, private val prefs: PeoplePrefs) {

    /** Group id → title of every label on this phone (empty when contacts can't be read). */
    private fun groupTitles(): Map<Long, String> = runCatching { c.contacts.groups().associate { it.id to it.title } }.getOrDefault(emptyMap())

    /** One-off upgrade of id-based references; cheap and idempotent, run at app start off the main thread. */
    suspend fun migrate() = withContext(Dispatchers.IO) {
        val rules = c.blocks.allRules()
        val needsGroups = rules.any { LabelRefs.isLegacyRule(it) } ||
            c.settings.current().screening.offHours.labelId != null ||
            c.calling.config.value.rules.any { LabelRefs.isLegacyLimit(it) }
        val groups = if (needsGroups) groupTitles() else emptyMap()
        rules.filter { LabelRefs.isLegacyRule(it) }.forEach { c.blocks.saveRule(LabelRefs.migrateRule(it, groups)) }
        if (c.settings.current().screening.offHours.labelId != null) {
            c.settings.update { s -> s.copy(screening = s.screening.copy(offHours = LabelRefs.migrateOffHours(s.screening.offHours, groups))) }
        }
        c.calling.update { LabelRefs.migrateConfig(it, groups) }
        // "Ringtone for label" used to save an allow rule: move the tone to the label page and drop the rule,
        // which also (unintentionally) let the label ring through off hours.
        val (add, drop) = LabelRefs.liftRuleRingtones(c.blocks.allRules(), prefs.current().labelRingtones)
        if (add.isNotEmpty()) prefs.update { it.copy(labelRingtones = it.labelRingtones + add.filterKeys { k -> k !in it.labelRingtones }) }
        drop.forEach { c.blocks.deleteRule(it) }
    }

    /** Labels were renamed or merged ([renames]: old title → new title). */
    suspend fun renamed(renames: Map<String, String>) = withContext(Dispatchers.IO) {
        if (renames.isEmpty()) return@withContext
        val rules = c.blocks.allRules().filter { it.type == RuleType.LABEL }
        val moved = LabelRefs.renameRules(rules, renames)
        rules.zip(moved).filter { (a, b) -> a != b }.forEach { (_, b) -> c.blocks.saveRule(b) }
        c.settings.update { s -> s.copy(screening = s.screening.copy(offHours = LabelRefs.renameOffHours(s.screening.offHours, renames))) }
        // Situations letting a label ring follow it, and so does what the one on now puts back.
        c.situations.labelsRenamed(renames)
        c.calling.update { LabelRefs.renameConfig(it, renames) }
        prefs.update { it.copy(labelRingtones = LabelRefs.renameRingtones(it.labelRingtones, renames)) }
        // The label's SIM, rhythm and Do Not Disturb choice follow it.
        c.extras.labelsRenamed(renames)
        // A label's safe word (I4) follows it too.
        c.familySafety.labelsRenamed(renames)
    }

    /**
     * Labels were deleted: their rules, limits and ringtones go too. Returns a sentence for the user when off
     * hours had to be switched off (it let only that label ring), else null.
     */
    suspend fun deleted(titles: Set<String>): String? = withContext(Dispatchers.IO) {
        if (titles.isEmpty()) return@withContext null
        c.blocks.allRules().filter { it.type == RuleType.LABEL && LabelRefs.refersTo(it.labelKey, titles) }.forEach { c.blocks.deleteRule(it.id) }
        c.calling.update { LabelRefs.deleteFromConfig(it, titles) }
        prefs.update { s -> s.copy(labelRingtones = s.labelRingtones.filterKeys { !LabelRefs.refersTo(it, titles) }) }
        c.extras.labelsDeleted(titles)
        c.familySafety.labelsDeleted(titles)
        val oh = c.settings.current().screening.offHours
        // While a Situation is on, off hours holds its values: what it puts back is the user's own (said below).
        val situationOn = c.situations.state.value.activeId != null
        val gone = oh.allow == OffHoursAllow.LABEL && LabelRefs.refersTo(oh.labelTitle, titles)
        if (gone) c.settings.update { s -> s.copy(screening = s.screening.copy(offHours = LabelRefs.labelGone(s.screening.offHours))) }
        // Situations letting the label ring let Favourites ring instead; the one on now takes that at once.
        val own = c.situations.labelsDeleted(titles)
        val offFor = if (situationOn) own else oh.labelTitle?.trim()?.takeIf { gone && oh.enabled }
        offFor?.let { c.appContext.getString(R.string.data_label_offhours_off, it) }
    }

    /** What [deleted] takes away for some labels, kept so an Undo can put it back ([restore]). */
    class Snapshot internal constructor(
        val rules: List<BlockRule>,
        val limits: List<LimitRule>,
        val ringtones: Map<String, String>,
        val policies: Map<String, LabelPolicy>,
        val safeWords: Map<String, SafeWord>,
        val offHours: OffHours,
        /** Contacts Parley had starred for these labels' "Allow through Do Not Disturb" (key → those labels). */
        val dndStars: Map<String, Set<String>> = emptyMap(),
    )

    /** Everything that names [titles] now (read before they're deleted). */
    suspend fun snapshot(titles: Set<String>): Snapshot = withContext(Dispatchers.IO) {
        Snapshot(
            rules = c.blocks.allRules().filter { it.type == RuleType.LABEL && LabelRefs.refersTo(it.labelKey, titles) },
            limits = LabelRefs.limitsOf(c.calling.config.value, titles),
            ringtones = LabelRefs.entriesOf(prefs.current().labelRingtones, titles),
            policies = LabelRefs.entriesOf(c.extras.policies.value, titles),
            safeWords = c.familySafety.storedSafeWords(titles),
            offHours = c.settings.current().screening.offHours,
            dndStars = c.extras.dndStars.value.mapValues { (_, l) -> l.intersect(titles) }.filterValues { it.isNotEmpty() },
        )
    }

    /** Puts a [snapshot] back after its labels were made again; whatever was set again meanwhile stays. */
    suspend fun restore(snapshot: Snapshot) = withContext(Dispatchers.IO) {
        val have = c.blocks.allRules().map { it.id }.toSet()
        snapshot.rules.filter { it.id !in have }.forEach { c.blocks.saveRule(it) }
        c.calling.update { LabelRefs.undoDeleteLimits(it, snapshot.limits) }
        prefs.update { it.copy(labelRingtones = LabelRefs.undoDeleteEntries(it.labelRingtones, snapshot.ringtones)) }
        c.extras.updatePolicies { LabelRefs.undoDeleteEntries(it, snapshot.policies) }
        // Members starred for a label that lets people through Do Not Disturb are starred again, or the policy would
        // read "on" while Do Not Disturb silences them.
        val dnd = c.extras.policies.value.filterValues { it.allowThroughDnd }.keys
        c.extras.restoreDndStars(snapshot.dndStars.mapValues { (_, l) -> l.intersect(dnd) })
        c.familySafety.restoreSafeWords(snapshot.safeWords)
        c.settings.update { s -> s.copy(screening = s.screening.copy(offHours = LabelRefs.undoDeleteOffHours(s.screening.offHours, snapshot.offHours))) }
    }
}
