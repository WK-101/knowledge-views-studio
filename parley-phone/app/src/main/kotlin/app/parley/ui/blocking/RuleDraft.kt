package app.parley.ui.blocking

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import app.parley.common.BlockAction
import app.parley.common.BlockRule
import app.parley.common.NotifyLevel
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.Schedule

/**
 * The rule editor's draft in saved state, so rotation, a theme or font change and the process being stopped keep
 * what was typed and picked. Only what a bundle holds (strings, numbers, booleans); an unknown enum name (from an
 * older version) falls back to the default.
 */
internal val RuleDraftSaver: Saver<BlockRule, Any> = listSaver(
    save = { r ->
        listOf(
            r.id, r.pattern, r.type.name, r.action.name, r.enabled, r.note, r.kind.name, r.simId,
            r.schedule?.days, r.schedule?.startMinute, r.schedule?.endMinute, r.notify.name, r.ringtone, r.expiresAt,
            r.hitCount, r.lastHitAt, r.label,
        )
    },
    restore = { v ->
        fun <E : Enum<E>> enumOr(values: Array<E>, name: Any?, default: E) = values.firstOrNull { it.name == name } ?: default
        val days = v[8] as Int?
        BlockRule(
            id = v[0] as Long,
            pattern = v[1] as String,
            type = enumOr(RuleType.entries.toTypedArray(), v[2], RuleType.PREFIX),
            action = enumOr(BlockAction.entries.toTypedArray(), v[3], BlockAction.REJECT),
            enabled = v[4] as Boolean,
            note = v[5] as String?,
            kind = enumOr(RuleKind.entries.toTypedArray(), v[6], RuleKind.BLOCK),
            simId = v[7] as String?,
            schedule = days?.let { Schedule(it, v[9] as Int, v[10] as Int) },
            notify = enumOr(NotifyLevel.entries.toTypedArray(), v[11], NotifyLevel.DEFAULT),
            ringtone = v[12] as String?,
            expiresAt = v[13] as Long?,
            hitCount = v[14] as Int,
            lastHitAt = v[15] as Long?,
            label = v[16] as String?,
        )
    },
)
