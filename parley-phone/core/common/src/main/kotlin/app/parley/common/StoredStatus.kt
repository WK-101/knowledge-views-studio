package app.parley.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A status line kept on disk ("last backup", "last sync") as what happened ([kind] and its [args]), never as
 * text: the text is rendered in the user's current language when shown, so changing the app language never
 * leaves a line in the old one. Values written before this (plain text) decode to null and are shown as they are.
 */
@Serializable
data class StoredStatus(val kind: String, val args: List<String> = emptyList()) {
    fun int(i: Int): Int = args.getOrNull(i)?.toIntOrNull() ?: 0

    fun encode(): String = PREFIX + json.encodeToString(serializer(), this)

    companion object {
        private const val PREFIX = "status:"
        private val json = Json { ignoreUnknownKeys = true }

        fun of(kind: String, vararg args: Any?): StoredStatus = StoredStatus(kind, args.map { it?.toString().orEmpty() })

        /** The status in [stored], or null for text from older versions (show that as it is). */
        fun decode(stored: String?): StoredStatus? {
            if (stored == null || !stored.startsWith(PREFIX)) return null
            return runCatching { json.decodeFromString(serializer(), stored.removePrefix(PREFIX)) }.getOrNull()
        }
    }
}
