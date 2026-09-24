package app.parley.data.calls

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.VoicemailContract
import android.provider.VoicemailContract.Status
import android.provider.VoicemailContract.Voicemails
import app.parley.common.calls.VoicemailFiles
import app.parley.data.Permissions
import app.parley.data.changes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** One voicemail from Android's voicemail store ([VoicemailContract]). */
data class Voicemail(
    val id: Long,
    val number: String,
    val date: Long,
    val durationSec: Long,
    val heard: Boolean,
    /** The audio has been downloaded to the phone (otherwise it can't be played yet). */
    val hasAudio: Boolean,
    val mimeType: String?,
    val transcription: String?,
    /** The app that stored it: the carrier's visual voicemail app or Android's built-in one. */
    val sourcePackage: String?,
    val accountId: String?,
) {
    val uri: Uri get() = ContentUris.withAppendedId(Voicemails.CONTENT_URI, id)
}

/** A visual voicemail source's state, from [VoicemailContract.Status]. */
data class VoicemailSource(
    val sourcePackage: String,
    val accountId: String?,
    val configured: Boolean,
    /** "Can't reach the voicemail server", or null when fine. */
    val problem: String?,
    val settingsUri: Uri?,
    val accessUri: Uri?,
    val quotaUsed: Int?,
    val quotaTotal: Int?,
)

data class VoicemailState(
    val items: List<Voicemail> = emptyList(),
    val sources: List<VoicemailSource> = emptyList(),
    /** False when Android refused access (Parley isn't the default phone app). */
    val available: Boolean = false,
    val loaded: Boolean = false,
) {
    val unheard: Int get() = items.count { !it.heard }
}

/**
 * The voicemail inbox (V1), read from Android's voicemail store.
 *
 * Access: AOSP's `VoicemailPermissions.callerHasReadAccess/WriteAccess` give **the default (or system) dialer** full
 * read and write access to every voicemail, without READ_VOICEMAIL / WRITE_VOICEMAIL (those are signature|privileged
 * permissions no store app can hold, and Parley doesn't declare them). So this only works while Parley is the default
 * phone app; otherwise the provider throws SecurityException and [VoicemailState.available] is false.
 *
 * Parley has no internet access: it shows what the carrier's visual voicemail app (or Android's built-in one) has
 * already downloaded, and can only *ask* that app to download a message's audio ([requestDownload]).
 */
class VoicemailRepository(private val context: Context, scope: CoroutineScope) {
    private val cr = context.contentResolver
    private val reload = MutableStateFlow(0)

    val state: StateFlow<VoicemailState> = combine(
        cr.changes(Voicemails.CONTENT_URI, retry = reload),
        cr.changes(Status.CONTENT_URI, retry = reload),
        reload,
    ) { _, _, _ -> }
        .map { load() }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, VoicemailState())

    fun refresh() {
        reload.value++
    }

    private fun load(): VoicemailState {
        if (!Permissions.isDefaultDialer(context)) return VoicemailState(loaded = true)
        val items = ArrayList<Voicemail>()
        val projection = arrayOf(
            Voicemails._ID, Voicemails.NUMBER, Voicemails.DATE, Voicemails.DURATION, Voicemails.IS_READ,
            Voicemails.HAS_CONTENT, Voicemails.MIME_TYPE, Voicemails.TRANSCRIPTION, Voicemails.SOURCE_PACKAGE,
            Voicemails.PHONE_ACCOUNT_ID,
        )
        try {
            cr.query(Voicemails.CONTENT_URI, projection, "${Voicemails.DELETED} = 0", null, "${Voicemails.DATE} DESC")?.use { c ->
                while (c.moveToNext()) {
                    items += Voicemail(
                        id = c.getLong(0),
                        number = c.getString(1).orEmpty(),
                        date = c.getLong(2),
                        durationSec = c.getLong(3),
                        heard = c.getInt(4) != 0,
                        hasAudio = c.getInt(5) != 0,
                        mimeType = c.getString(6),
                        transcription = c.getString(7)?.takeIf { it.isNotBlank() },
                        sourcePackage = c.getString(8),
                        accountId = c.getString(9),
                    )
                }
            }
        } catch (_: SecurityException) {
            return VoicemailState(loaded = true)
        } catch (_: IllegalArgumentException) {
            return VoicemailState(loaded = true)
        }
        return VoicemailState(items, sources(), available = true, loaded = true)
    }

    private fun sources(): List<VoicemailSource> = try {
        cr.query(
            Status.CONTENT_URI,
            arrayOf(
                Status.SOURCE_PACKAGE, Status.PHONE_ACCOUNT_ID, Status.CONFIGURATION_STATE, Status.DATA_CHANNEL_STATE,
                Status.NOTIFICATION_CHANNEL_STATE, Status.SETTINGS_URI, Status.VOICEMAIL_ACCESS_URI, Status.QUOTA_OCCUPIED, Status.QUOTA_TOTAL,
            ),
            null, null, null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val config = c.getInt(2)
                    val data = c.getInt(3)
                    val notif = c.getInt(4)
                    add(
                        VoicemailSource(
                            sourcePackage = c.getString(0).orEmpty(),
                            accountId = c.getString(1),
                            configured = config == Status.CONFIGURATION_STATE_OK,
                            problem = when {
                                config == Status.CONFIGURATION_STATE_CAN_BE_CONFIGURED -> context.getString(app.parley.data.R.string.data_vm_can_configure)
                                config == Status.CONFIGURATION_STATE_NOT_CONFIGURED -> context.getString(app.parley.data.R.string.data_vm_not_configured)
                                data == Status.DATA_CHANNEL_STATE_NO_CONNECTION || data == Status.DATA_CHANNEL_STATE_NO_CONNECTION_CELLULAR_REQUIRED ->
                                    context.getString(app.parley.data.R.string.data_vm_no_data)
                                data != Status.DATA_CHANNEL_STATE_OK -> context.getString(app.parley.data.R.string.data_vm_server_problem)
                                notif == Status.NOTIFICATION_CHANNEL_STATE_NO_CONNECTION -> context.getString(app.parley.data.R.string.data_vm_no_connection)
                                else -> null
                            },
                            settingsUri = c.getString(5)?.takeIf { it.isNotBlank() }?.let(Uri::parse),
                            accessUri = c.getString(6)?.takeIf { it.isNotBlank() }?.let(Uri::parse),
                            quotaUsed = c.getInt(7).takeIf { it >= 0 && !c.isNull(7) },
                            quotaTotal = c.getInt(8).takeIf { it > 0 && !c.isNull(8) },
                        ),
                    )
                }
            }
        }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    /** Marks voicemails heard (the source app syncs this to the server when it can). */
    suspend fun markHeard(ids: Collection<Long>, heard: Boolean = true) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        runCatching {
            cr.update(Voicemails.CONTENT_URI, ContentValues().apply { put(Voicemails.IS_READ, if (heard) 1 else 0) }, "${Voicemails._ID} IN (${ids.joinToString(",")})", null)
        }
    }

    /**
     * Deletes a voicemail. It's marked deleted first, which tells the source app to delete it on the server too;
     * when that isn't possible the row is removed here only.
     */
    suspend fun delete(v: Voicemail): Boolean = withContext(Dispatchers.IO) {
        val marked = runCatching { cr.update(v.uri, ContentValues().apply { put(Voicemails.DELETED, 1) }, null, null) > 0 }.getOrDefault(false)
        marked || runCatching { cr.delete(v.uri, null, null) > 0 }.getOrDefault(false)
    }

    /**
     * Asks the app that owns [v] to download its audio (Parley itself can't: it has no internet access). Returns false
     * when there's no source app to ask.
     */
    fun requestDownload(v: Voicemail): Boolean {
        val pkg = v.sourcePackage?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            context.sendBroadcast(Intent(VoicemailContract.ACTION_FETCH_VOICEMAIL, v.uri).setPackage(pkg))
            true
        }.getOrDefault(false)
    }

    /** Copies the audio into Parley's share cache (served by its FileProvider) and returns the file, or null. */
    suspend fun copyForSharing(v: Voicemail): File? = withContext(Dispatchers.IO) {
        if (!v.hasAudio) return@withContext null
        val t = Instant.ofEpochMilli(v.date).atZone(ZoneId.systemDefault())
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        // Only the newest shared voicemail is kept.
        dir.listFiles { f -> f.name.startsWith("voicemail-") }?.forEach { it.delete() }
        val out = File(dir, VoicemailFiles.shareName(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, v.mimeType))
        try {
            cr.openInputStream(v.uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return@withContext null
            out
        } catch (_: Exception) {
            out.delete()
            null
        }
    }

    companion object {
        /** Android's voicemail settings screen (carrier visual voicemail on/off, number, notifications). */
        const val CONFIGURE_ACTION: String = android.telephony.TelephonyManager.ACTION_CONFIGURE_VOICEMAIL
    }
}
