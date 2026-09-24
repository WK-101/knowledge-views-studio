package app.parley.common.calltime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a limit rule applies to. The most specific matching scope wins: contact › label › SIM › all calls. */
@Serializable
enum class LimitScope { CONTACT, LABEL, SIM, GLOBAL }

/**
 * One call-time rule (T5, T6). A rule with no limit and no quota is never stored: removing all values
 * removes the rule.
 */
@Serializable
data class LimitRule(
    val scope: LimitScope,
    /** Contact lookup key, label (group) id, phone-account id, or "" for [LimitScope.GLOBAL]. */
    val key: String = "",
    /** Shown in lists and in the call ("Ana", "Family", "Work SIM"). */
    val title: String = "",
    /** Hard limit per call, in minutes (0 = none). */
    val perCallMinutes: Int = 0,
    /** Daily allowance in minutes, counted from the call history (0 = none). */
    val dailyMinutes: Int = 0,
    /** Weekly allowance in minutes (0 = none). */
    val weeklyMinutes: Int = 0,
    val incoming: Boolean = true,
    val outgoing: Boolean = true,
) {
    val id: String get() = "${scope.name}:$key"
    val isEmpty: Boolean get() = perCallMinutes <= 0 && dailyMinutes <= 0 && weeklyMinutes <= 0
    val hasQuota: Boolean get() = dailyMinutes > 0 || weeklyMinutes > 0
    fun appliesTo(incomingCall: Boolean): Boolean = if (incomingCall) incoming else outgoing
}

/** Soft talk-time reminders (T1): a beep in the earpiece and/or a vibration. They never end a call. */
@Serializable
data class ReminderSettings(
    /** Minutes between reminders during every call (0 = off). */
    val everyMinutes: Int = 0,
    val beep: Boolean = true,
    val vibrate: Boolean = true,
    /** Contact lookup key → minutes (0 = no reminders for this contact). Replaces [everyMinutes] for them. */
    val perContact: Map<String, Int> = emptyMap(),
)

/**
 * Everything about calling and call time that Parley stores for itself (kept out of the shared
 * settings store so the call path reads one small object).
 */
@Serializable
data class CallingConfig(
    /** Vibrate on connect, disconnect, swap, merge and limit warnings (A6). */
    val haptics: Boolean = true,
    val reminders: ReminderSettings = ReminderSettings(),
    val rules: List<LimitRule> = emptyList(),
    /** Warning before a hard limit ends the call. */
    val warnSeconds: Int = 60,
    /** When an allowance is used up, incoming calls from that person ring silently. */
    val silenceIncomingOverQuota: Boolean = false,
    /** Limits can only be changed after unlocking with the app lock, and can't be extended during a call (T7). */
    val supervised: Boolean = false,
    /** Contacts (lookup keys) that are never limited, typically favourites. */
    val neverLimit: Set<String> = emptySet(),
    /** The notification-health problems the user dismissed the home banner for ("" = none). */
    val healthBannerDismissed: String = "",
) {
    fun rule(scope: LimitScope, key: String): LimitRule? = rules.firstOrNull { it.scope == scope && it.key == key }

    /** Replaces (or removes, when empty) the rule with the same scope and key. */
    fun withRule(rule: LimitRule): CallingConfig {
        val rest = rules.filterNot { it.id == rule.id }
        return copy(rules = if (rule.isEmpty) rest else rest + rule)
    }

    fun withoutRule(scope: LimitScope, key: String): CallingConfig = copy(rules = rules.filterNot { it.scope == scope && it.key == key })

    companion object {
        val WARN_CHOICES = listOf(30, 60, 120)
        val REMINDER_CHOICES = listOf(0, 5, 10, 15, 20, 30, 45, 60)
    }
}

/** One carrier reply to a USSD code (A13), kept on this phone only. */
@Serializable
data class UssdEntry(
    val code: String,
    val reply: String,
    val at: Long,
    val ok: Boolean,
    val simLabel: String? = null,
)

object CallingJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; coerceInputValues = true }

    fun encode(config: CallingConfig): String = json.encodeToString(CallingConfig.serializer(), config)

    /** A missing or damaged file gives the defaults (everything off), never a crash on the call path. */
    fun decode(text: String?): CallingConfig =
        if (text.isNullOrBlank()) CallingConfig() else runCatching { json.decodeFromString(CallingConfig.serializer(), text) }.getOrDefault(CallingConfig())

    fun encodeUssd(list: List<UssdEntry>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(UssdEntry.serializer()), list)

    fun decodeUssd(text: String?): List<UssdEntry> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(UssdEntry.serializer()), text) }.getOrDefault(emptyList())
}
