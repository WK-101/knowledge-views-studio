package app.parley.common.spam

import app.parley.common.BlockAction
import app.parley.common.ListMode
import app.parley.common.NotifyLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** UPDATER: copied from the optional "Parley Lists" companion app (B4c). */
enum class PackOrigin { FILE, FOLDER, BUILTIN, UPDATER }

/** An installed pack and the user's choices for it. */
@Serializable
data class PackState(
    val id: String,
    val name: String,
    val publisher: String = "",
    val source: String = "",
    val licence: String = "",
    val version: Long = 1,
    /** When the publisher built this version. */
    val created: Long = 0,
    val ttlDays: Int = 30,
    val regions: List<String> = emptyList(),
    val categories: Map<String, String> = emptyMap(),
    val entries: Int = 0,
    val ranges: Int = 0,
    val signed: Boolean = false,
    val fingerprint: String? = null,
    /** When this version was installed on the phone. */
    val installedAt: Long = 0,
    val origin: PackOrigin = PackOrigin.FILE,
    val enabled: Boolean = true,
    /** Warn is the default; blocking is opt-in per pack. */
    val mode: ListMode = ListMode.WARN,
    val threshold: Int = 50,
    val action: BlockAction = BlockAction.REJECT,
    val useRanges: Boolean = true,
    val notify: NotifyLevel = NotifyLevel.DEFAULT,
    /** E.164 numbers the user marked "Not spam" for this pack. */
    val suppressed: List<String> = emptyList(),
) {
    fun isStale(now: Long): Boolean = ttlDays > 0 && created > 0 && now - created > ttlDays * 86_400_000L

    fun categoryName(id: Int): String? = categories[id.toString()]
}

@Serializable
data class ListsState(
    val packs: List<PackState> = emptyList(),
    /** Subscribed folder (SAF tree URI). */
    val folderUri: String? = null,
    val folderCheckedAt: Long = 0,
    /** File name → last-modified already imported from the folder. */
    val folderSeen: Map<String, Long> = emptyMap(),
    val folderError: String? = null,
    /** Built-in packs the user dismissed the suggestion for. */
    val dismissedSuggestions: List<String> = emptyList(),
) {
    fun encode(): String = CODEC.encodeToString(serializer(), this)

    companion object {
        private val CODEC = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(s: String?): ListsState = if (s.isNullOrBlank()) ListsState() else runCatching { CODEC.decodeFromString(serializer(), s) }.getOrDefault(ListsState())
    }
}
