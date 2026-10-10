package app.parley.common.calltime

import app.parley.common.Codecs
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** What a limit rule applies to. The most specific matching scope wins: contact › label › SIM › all calls. */
@Serializable
enum class LimitScope { CONTACT, LABEL, SIM, GLOBAL }

/**
 * One call-time rule. A rule with no limit and no quota is never stored: removing all values
 * removes the rule.
 */
@Serializable
data class LimitRule(
    val scope: LimitScope,
    /**
     * Contact lookup key, label title (older versions stored the group id: see [app.parley.common.LabelRefs]),
     * phone-account id, or "" for [LimitScope.GLOBAL].
     */
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

/** What call time keeps for one contact alone (see [CallingConfig.contactPart]). */
data class ContactCallTime(val rule: LimitRule? = null, val reminderMinutes: Int? = null, val neverLimit: Boolean = false) {
    val isEmpty: Boolean get() = rule == null && reminderMinutes == null && !neverLimit
}

/** Soft talk-time reminders: a beep in the earpiece and/or a vibration. They never end a call. */
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
    /** Vibrate on connect, disconnect, swap, merge and limit warnings. */
    val haptics: Boolean = true,
    /** The buzz when a call connects (with [haptics] on). Answer and decline keep their own, distinct buzz. */
    val connectHaptic: Boolean = true,
    val reminders: ReminderSettings = ReminderSettings(),
    val rules: List<LimitRule> = emptyList(),
    /** Warning before a hard limit ends the call. */
    val warnSeconds: Int = 60,
    /** When an allowance is used up, incoming calls from that person ring silently. */
    val silenceIncomingOverQuota: Boolean = false,
    /** Limits can only be changed after unlocking with the app lock, and can't be extended during a call. */
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

    /** What is set for contact [key] alone: its limit, its reminder and "never limit". */
    fun contactPart(key: String): ContactCallTime =
        ContactCallTime(rule(LimitScope.CONTACT, key), reminders.perContact[key], key in neverLimit)

    /** [part] set for contact [key] (named [title] in lists), replacing what it had. */
    fun withContactPart(key: String, part: ContactCallTime, title: String): CallingConfig {
        val base = withoutContact(key)
        return base.copy(
            rules = base.rules + listOfNotNull(part.rule?.copy(key = key, title = title)),
            reminders = base.reminders.copy(perContact = base.reminders.perContact + listOfNotNull(part.reminderMinutes?.let { key to it })),
            neverLimit = if (part.neverLimit) base.neverLimit + key else base.neverLimit,
        )
    }

    /** Nothing set for contact [key] any more. */
    fun withoutContact(key: String): CallingConfig = copy(
        rules = rules.filterNot { it.scope == LimitScope.CONTACT && it.key == key },
        reminders = reminders.copy(perContact = reminders.perContact - key),
        neverLimit = neverLimit - key,
    )

    /**
     * Contact [from]'s limit, reminder and "never limit" moved to [to] (a contact made private or visible), named
     * [title]: a private contact's is empty, so its name is never kept outside the vault.
     */
    fun rekeyed(from: String, to: String, title: String): CallingConfig {
        val part = contactPart(from)
        if (part.isEmpty) return this
        return withoutContact(from).withContactPart(to, part, title)
    }

    /** This config without anything set for contacts whose key [private] says is a private contact's. */
    fun withoutContacts(private: (String) -> Boolean): CallingConfig = copy(
        rules = rules.filterNot { it.scope == LimitScope.CONTACT && private(it.key) },
        reminders = reminders.copy(perContact = reminders.perContact.filterKeys { !private(it) }),
        neverLimit = neverLimit.filterNot(private).toSet(),
    )

    companion object {
        val WARN_CHOICES = listOf(30, 60, 120)
        val REMINDER_CHOICES = listOf(0, 5, 10, 15, 20, 30, 45, 60)
    }
}

/** One carrier reply to a USSD code, kept on this phone only. */
@Serializable
data class UssdEntry(
    val code: String,
    val reply: String,
    val at: Long,
    val ok: Boolean,
    val simLabel: String? = null,
)

object CallingJson {
    private val json = Codecs.tolerant

    fun encode(config: CallingConfig): String = json.encodeToString(CallingConfig.serializer(), config)

    /** A missing or damaged file gives the defaults (everything off), never a crash on the call path. */
    fun decode(text: String?): CallingConfig =
        if (text.isNullOrBlank()) CallingConfig() else runCatching { json.decodeFromString(CallingConfig.serializer(), text) }.getOrDefault(CallingConfig())

    fun encodeUssd(list: List<UssdEntry>): String = json.encodeToString(ListSerializer(UssdEntry.serializer()), list)

    fun decodeUssd(text: String?): List<UssdEntry> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(ListSerializer(UssdEntry.serializer()), text) }.getOrDefault(emptyList())
}
