package app.parley.data.people

import app.parley.common.LabelRefs
import app.parley.common.OffHoursAllow
import app.parley.common.RuleType
import app.parley.data.DataContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps everything that names a label (block/allow rules, off hours, call-time limits, label ringtones) pointing
 * at a label that exists, by title (see [LabelRefs]):
 * - [migrate] rewrites references saved by older versions (group row ids) and moves label ringtones that were
 *   kept on allow rules to the label page's store;
 * - [renamed] and [deleted] follow a rename, merge or delete made on the labels screen.
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
        c.calling.update { LabelRefs.renameConfig(it, renames) }
        prefs.update { it.copy(labelRingtones = LabelRefs.renameRingtones(it.labelRingtones, renames)) }
        // X3: the label's SIM, rhythm and Do Not Disturb choice follow it.
        c.extras.labelsRenamed(renames)
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
        val oh = c.settings.current().screening.offHours
        if (oh.allow == OffHoursAllow.LABEL && LabelRefs.refersTo(oh.labelTitle, titles)) {
            c.settings.update { s -> s.copy(screening = s.screening.copy(offHours = LabelRefs.labelGone(s.screening.offHours))) }
            if (oh.enabled) c.appContext.getString(app.parley.data.R.string.data_label_offhours_off, oh.labelTitle?.trim().toString()) else null
        } else {
            null
        }
    }
}
