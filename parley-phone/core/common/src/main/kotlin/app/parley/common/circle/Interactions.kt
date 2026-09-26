package app.parley.common.circle

import app.parley.common.Messenger
import app.parley.common.MessengerApp
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * R2: a contact that isn't a phone call (the call log is the only source of calls): a meeting, a message, a video
 * call or anything else, with an optional note. Stored per contact under its lookup key.
 */
enum class InteractionType { MEET, MESSAGE, VIDEO, OTHER }

/**
 * R3: where Parley started a conversation. Each channel has its own "Log this?" choice ([LogMode]). Calls are never
 * a channel: they come from the call log only, so nothing is ever counted twice.
 */
enum class InteractionChannel(val type: InteractionType) {
    SMS(InteractionType.MESSAGE),
    WHATSAPP(InteractionType.MESSAGE),
    SIGNAL(InteractionType.MESSAGE),
    TELEGRAM(InteractionType.MESSAGE),

    /** Viber, Threema, Element and the other messengers Parley can open. */
    OTHER_APP(InteractionType.MESSAGE),

    /** A video call started from Parley (any app). */
    VIDEO(InteractionType.VIDEO),
    ;

    companion object {
        fun decode(name: String?): InteractionChannel? = entries.firstOrNull { it.name == name }

        fun forMessenger(m: Messenger?): InteractionChannel = when (m) {
            Messenger.WHATSAPP -> WHATSAPP
            Messenger.SIGNAL -> SIGNAL
            Messenger.TELEGRAM -> TELEGRAM
            Messenger.VIBER, null -> OTHER_APP
        }

        /** The message channel of an app package (a messenger's own contact row); unknown apps are [OTHER_APP]. */
        fun forPackage(pkg: String?): InteractionChannel = forMessenger(MessengerApp.forPackage(pkg)?.messenger)
    }
}

/** R3: what happens after Parley opens a chat or video call with someone in your circle. */
enum class LogMode { ALWAYS, ASK, NEVER }

/** Kinds of "last in touch": a call from the call log, or a logged interaction. */
enum class ContactKind { CALL, MEET, MESSAGE, VIDEO, OTHER }

data class LastContact(val time: Long, val kind: ContactKind)

/**
 * One logged interaction as it travels inside a private contact's (sealed) vault entry while the contact is private
 * ("Move to private" and back): kind [t], channel [c], time [at], the opened [note] and the unique key [u].
 */
@Serializable
data class CarriedInteraction(val t: String, val c: String? = null, val at: Long, val note: String? = null, val u: String)

object Interactions {
    /** R3: prompts for the same channel and person within this window are one conversation. */
    const val BUCKET_MS = 10 * 60_000L

    /** How long after leaving Parley the "Log this?" question is still asked on return. */
    const val PROMPT_TTL_MS = 60 * 60_000L

    /**
     * Unique key of an interaction logged from a launch: (channel, person, 10-minute bucket), so accepting the
     * question twice, or "Always" plus a second launch a minute later, records one entry.
     */
    fun promptKey(channel: InteractionChannel, lookupKey: String, time: Long): String = "p:${channel.name}:$lookupKey:${time / BUCKET_MS}"

    /** Unique key of an entry added by hand (never collides with another). */
    fun manualKey(nonce: String): String = "m:$nonce"

    /** Unique key of a "Mark as wished" entry: one per occasion. */
    fun wishedKey(occurrence: String): String = "w:$occurrence"

    private val carriedJson = Json { ignoreUnknownKeys = true }

    /** Interactions carried into the vault (see [CarriedInteraction]); null when there are none. */
    fun encodeCarried(list: List<CarriedInteraction>): String? =
        if (list.isEmpty()) null else carriedJson.encodeToString(ListSerializer(CarriedInteraction.serializer()), list)

    /** Reads [encodeCarried]'s text; anything unreadable reads as none. */
    fun decodeCarried(text: String?): List<CarriedInteraction> = if (text.isNullOrBlank()) {
        emptyList()
    } else {
        runCatching { carriedJson.decodeFromString(ListSerializer(CarriedInteraction.serializer()), text) }.getOrDefault(emptyList())
    }

    fun kindOf(type: InteractionType): ContactKind = when (type) {
        InteractionType.MEET -> ContactKind.MEET
        InteractionType.MESSAGE -> ContactKind.MESSAGE
        InteractionType.VIDEO -> ContactKind.VIDEO
        InteractionType.OTHER -> ContactKind.OTHER
    }

    /**
     * The latest contact with a person: their newest answered call or newest interaction, whichever is later.
     * Missed and zero-length calls don't count.
     */
    fun lastContact(lastAnsweredCall: Long?, lastInteraction: Pair<Long, InteractionType>?): LastContact? {
        val call = lastAnsweredCall?.takeIf { it > 0 }?.let { LastContact(it, ContactKind.CALL) }
        val other = lastInteraction?.let { LastContact(it.first, kindOf(it.second)) }
        return listOfNotNull(call, other).maxByOrNull { it.time }
    }
}
