package com.todocompanion.app.domain.context

import com.todocompanion.app.data.entity.ContextEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GTD-style context availability. A context can be restricted to certain weekdays and a daily
 * time window (e.g. "@Office = Mon–Fri, 09:00–17:00"); outside that window — or when the context
 * is switched off — its tasks drop out of the Do-Next list, MLO-style. Fully on-device.
 *
 * [days] holds ISO weekday numbers (1 = Monday … 7 = Sunday). An empty [days] means "any day".
 * Times are minutes-from-midnight.
 */
@Serializable
data class OpenHours(
    val days: Set<Int> = emptySet(),
    val startMin: Int = 9 * 60,
    val endMin: Int = 17 * 60,
)

object ContextAvailability {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(oh: OpenHours): String = json.encodeToString(OpenHours.serializer(), oh)
    fun parse(s: String?): OpenHours? =
        if (s.isNullOrBlank()) null else runCatching { json.decodeFromString(OpenHours.serializer(), s) }.getOrNull()

    /** Is this context's window open at the given local weekday (1..7) + minute-of-day? */
    fun isOpen(oh: OpenHours, dayOfWeek: Int, minuteOfDay: Int): Boolean {
        if (oh.days.isNotEmpty() && dayOfWeek !in oh.days) return false
        return minuteOfDay in oh.startMin until oh.endMin
    }

    /** A context is available when it's active and either has no schedule or its window is open now. */
    fun isAvailable(ctx: ContextEntity, dayOfWeek: Int, minuteOfDay: Int): Boolean {
        if (!ctx.active) return false
        val oh = parse(ctx.openHoursJson) ?: return true
        return isOpen(oh, dayOfWeek, minuteOfDay)
    }
}
