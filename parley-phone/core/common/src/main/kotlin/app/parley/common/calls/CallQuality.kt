package app.parley.common.calls

import app.parley.common.Codecs
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Quality facts of one call as Telecom reported them (L2): which SIM, Wi-Fi calling, HD voice, how and why it ended,
 * how long it lasted, and the subject the caller sent (L10). Kept on this phone for the call history and a later
 * quality diary; nothing here is sent anywhere.
 */
@Serializable
data class CallQualityFacts(
    /** Wall-clock time the call started (rang or was placed). */
    val startedAt: Long,
    val incoming: Boolean,
    /** Talk time in seconds (0 when it never connected). */
    val durationSec: Long = 0,
    val connected: Boolean = false,
    /** The SIM's name on dual-SIM phones ("Work"), else null. */
    val sim: String? = null,
    /** Went over Wi-Fi calling at some point while connected. */
    val wifi: Boolean = false,
    /** Carried in HD voice at some point while connected. */
    val hd: Boolean = false,
    /** Telecom's disconnect code. */
    val end: EndCode? = null,
    /** Telephony's own cause, as the reason text named it ("LOST_SIGNAL"), when it was a drop. */
    val cause: String? = null,
    val drop: DropKind? = null,
    /** The caller's subject (already cleaned by [CallSubject]). */
    val subject: String? = null,
)

/** Compact JSON for [CallQualityFacts] lists; unknown fields are ignored so older rows keep reading. */
object CallQualityCodec {
    private val json = Codecs.compact
    private val list = ListSerializer(CallQualityFacts.serializer())

    fun encode(items: List<CallQualityFacts>): String = json.encodeToString(list, items)

    fun decode(text: String?): List<CallQualityFacts> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(list, text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** The first telephony cause name in a disconnect reason ("IMS, LOST_SIGNAL" → "LOST_SIGNAL"), for the diary. */
    fun causeName(reason: String?): String? = reason?.split(',', ' ', ':', ';')?.map { it.trim() }
        ?.lastOrNull { it.length in 3..40 && it.all { c -> c.isUpperCase() || c.isDigit() || c == '_' } }

    /** The facts of the call at about [time] (a call-log date): the closest start within two minutes. */
    fun near(facts: List<CallQualityFacts>, time: Long): CallQualityFacts? =
        facts.filter { kotlin.math.abs(it.startedAt - time) <= NEAR_MS }.minByOrNull { kotlin.math.abs(it.startedAt - time) }

    private const val NEAR_MS = 120_000L
}
