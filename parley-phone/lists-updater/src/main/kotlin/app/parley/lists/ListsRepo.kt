package app.parley.lists

import android.content.Context
import app.parley.common.spam.Ed25519
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A community pack the user subscribed to: any HTTPS link to a `.parleylist` file. */
@Serializable
data class CommunitySource(val url: String, val addedAt: Long = 0)

@Serializable
data class UpdaterConfig(
    val ftcEnabled: Boolean = true,
    /** Rolling window of FTC reports that makes up the pack. */
    val ftcDays: Int = 30,
    val arcepEnabled: Boolean = true,
    val community: List<CommunitySource> = emptyList(),
    val auto: Boolean = true,
    val intervalHours: Int = 24,
    val unmeteredOnly: Boolean = true,
    val idleOnly: Boolean = true,
    val chargingOnly: Boolean = false,
)

/** A pack ready for Parley. */
@Serializable
data class PackInfo(
    val id: String,
    val name: String,
    val version: Long,
    val entries: Int,
    val ranges: Int,
    val sizeBytes: Long,
    val updatedAt: Long,
    val fingerprint: String? = null,
    val source: String = "",
    val licence: String = "",
    /** "ftc", "arcep" or "community". */
    val origin: String,
    /** For community packs: the link it came from. */
    val sourceUrl: String? = null,
)

@Serializable
data class SourceStatus(
    val lastAttempt: Long = 0,
    val lastSuccess: Long = 0,
    val error: String? = null,
    /** Bytes downloaded in the last run. */
    val downloaded: Long = 0,
    val etag: String? = null,
    val lastModified: String? = null,
)

@Serializable
data class UpdaterState(
    val config: UpdaterConfig = UpdaterConfig(),
    val packs: List<PackInfo> = emptyList(),
    /** Source key ("ftc", "arcep", or a community URL) → status. */
    val status: Map<String, SourceStatus> = emptyMap(),
    val lastRun: Long = 0,
    val lastRunError: String? = null,
    val running: Boolean = false,
)

/** Files and state of the updater. Everything lives in the app's private storage. */
class ListsRepo private constructor(context: Context) {
    private val app = context.applicationContext
    val packsDir: File = File(app.filesDir, "packs").apply { mkdirs() }
    val ftcDir: File = File(app.filesDir, "ftc").apply { mkdirs() }
    private val stateFile = File(app.filesDir, "state.json")
    private val keyFile = File(app.noBackupFilesDir, "pack.key")

    private val _state = MutableStateFlow(read())
    val state: StateFlow<UpdaterState> = _state.asStateFlow()

    private fun read(): UpdaterState = runCatching {
        if (stateFile.exists()) CODEC.decodeFromString(UpdaterState.serializer(), stateFile.readText()) else UpdaterState()
    }.getOrDefault(UpdaterState()).copy(running = false)

    @Synchronized
    fun update(f: (UpdaterState) -> UpdaterState): UpdaterState {
        val s = f(_state.value)
        val tmp = File(app.filesDir, "state.json.tmp")
        tmp.writeText(CODEC.encodeToString(UpdaterState.serializer(), s))
        if (!tmp.renameTo(stateFile)) {
            stateFile.writeText(tmp.readText())
            tmp.delete()
        }
        _state.value = s
        return s
    }

    fun setConfig(f: (UpdaterConfig) -> UpdaterConfig) = update { it.copy(config = f(it.config)) }

    fun packFile(id: String): File? = if (ID.matches(id)) File(packsDir, "$id.parleylist") else null

    /** This install's signing key for the packs it builds. Parley pins it: updates must come from the same key. */
    @Synchronized
    fun signingKey(): ByteArray {
        if (keyFile.exists() && keyFile.length() == 32L) return keyFile.readBytes()
        keyFile.parentFile?.mkdirs()
        val k = Ed25519.newSecret()
        keyFile.writeBytes(k)
        return k
    }

    fun fingerprint(): String = Ed25519.fingerprint(Ed25519.publicKey(signingKey()))

    fun totalBytes(): Long = packsDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun cacheBytes(): Long = ftcDir.listFiles()?.sumOf { it.length() } ?: 0L

    companion object {
        val ID = Regex("[A-Za-z0-9._-]{1,80}")
        private val CODEC = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        // Holds only the application context.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ListsRepo? = null

        fun get(context: Context): ListsRepo = instance ?: synchronized(this) { instance ?: ListsRepo(context).also { instance = it } }
    }
}
