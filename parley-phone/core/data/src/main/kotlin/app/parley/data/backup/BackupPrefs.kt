package app.parley.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.util.Base64
import app.parley.common.StoredStatus
import app.parley.common.backup.KeyBundle
import app.parley.common.backup.RetentionPolicy
import app.parley.data.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class BackupSchedule { OFF, DAILY, WEEKLY }

data class BackupState(
    val folderUri: String? = null,
    val folderName: String? = null,
    val schedule: BackupSchedule = BackupSchedule.OFF,
    /** 0 = smart (daily/weekly/monthly), otherwise keep the newest N. */
    val keepLast: Int = 0,
    val hasKeys: Boolean = false,
    val keyId: String? = null,
    val lastBackupAt: Long = 0,
    val lastBackupName: String? = null,
    val lastVerifiedAt: Long = 0,
    val lastContactCount: Int = -1,
    val lastContentHash: String? = null,
    /** Content hash of the newest backup that lacked a section; never the reference for rotation or "unchanged". */
    val lastIncompleteHash: String? = null,
    /** Stored as a [app.parley.common.StoredStatus] (older versions: text); shown with [resultText]. */
    val lastResult: String? = null,
    val rotationPaused: Boolean = false,
    /** Raw contacts the last restore created (raw, not aggregate ids: a restored entry may have joined an existing contact). */
    val lastRestoreIds: List<Long> = emptyList(),
    /** Newest backup that contains private contacts; rotation keeps it while newer ones lack them. */
    val lastVaultBackupName: String? = null,
    /**
     * Whether the backup key vouches for this phone's signing key, so this phone's backups show as yours on another
     * phone too. Set whenever the passphrase or recovery key is entered here (setup, change, restore, "confirm").
     */
    val signedAsYours: Boolean = false,
) {
    val policy: RetentionPolicy get() = if (keepLast > 0) RetentionPolicy.Simple(keepLast) else RetentionPolicy.Periodic(daily = 7, weekly = 5, monthly = 12, yearly = 3)

    /** The last result in the current language (rendered now, not when it was stored). */
    fun resultText(res: Resources): String? {
        val s = StoredStatus.decode(lastResult) ?: return lastResult
        return when (s.kind) {
            UNCHANGED -> res.getString(R.string.data_bkp_unchanged)
            FOLDER_GONE -> res.getString(R.string.data_bkp_folder_gone)
            NOT_VERIFIED -> res.getString(R.string.data_bkp_not_verified)
            FAILED -> res.getString(R.string.data_bkp_failed, s.args.getOrNull(0).orEmpty())
            RESULT -> res.getString(
                if (s.args.getOrNull(0) == "1") R.string.data_bkp_result_vault_missing else R.string.data_bkp_result,
                res.getQuantityString(R.plurals.data_contacts_count, s.int(1), s.int(1)),
                res.getQuantityString(R.plurals.data_calls_count, s.int(2), s.int(2)),
            )
            INCOMPLETE -> res.getString(
                R.string.data_bkp_result_incomplete,
                res.getQuantityString(R.plurals.data_contacts_count, s.int(1), s.int(1)),
                res.getQuantityString(R.plurals.data_calls_count, s.int(2), s.int(2)),
                s.args.getOrNull(0).orEmpty(),
            )
            else -> null
        }
    }

    companion object {
        const val UNCHANGED = "unchanged"
        const val FOLDER_GONE = "folder_gone"
        const val NOT_VERIFIED = "not_verified"
        const val FAILED = "failed"
        const val RESULT = "result"
        const val INCOMPLETE = "incomplete"
    }
}

/** Backup configuration and status (SharedPreferences; the key bundle holds no secret in clear). */
class BackupPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<BackupState> = _state

    private fun load() = BackupState(
        folderUri = prefs.getString("folder", null),
        folderName = prefs.getString("folderName", null),
        schedule = runCatching { BackupSchedule.valueOf(prefs.getString("schedule", "OFF")!!) }.getOrDefault(BackupSchedule.OFF),
        keepLast = prefs.getInt("keepLast", 0),
        hasKeys = prefs.contains("bundle"),
        keyId = prefs.getString("keyId", null),
        lastBackupAt = prefs.getLong("lastAt", 0),
        lastBackupName = prefs.getString("lastName", null),
        lastVerifiedAt = prefs.getLong("verifiedAt", 0),
        lastContactCount = prefs.getInt("lastCount", -1),
        lastContentHash = prefs.getString("lastHash", null),
        lastIncompleteHash = prefs.getString("gapHash", null),
        lastResult = prefs.getString("lastResult", null),
        rotationPaused = prefs.getBoolean("paused", false),
        lastRestoreIds = prefs.getString("restoreRawIds", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() },
        lastVaultBackupName = prefs.getString("vaultName", null),
        signedAsYours = prefs.getString("endorsedKeyId", null).let { it != null && it == prefs.getString("keyId", null) },
    )

    fun update(f: (SharedPreferences.Editor) -> Unit) {
        prefs.edit().also(f).apply()
        _state.value = load()
    }

    fun keyBundle(): KeyBundle? = prefs.getString("bundle", null)?.let { runCatching { KeyBundle.fromBytes(Base64.decode(it, Base64.NO_WRAP)) }.getOrNull() }

    fun saveKeyBundle(b: KeyBundle) = update {
        it.putString("bundle", Base64.encodeToString(b.toBytes(), Base64.NO_WRAP)).putString("keyId", b.keyId)
    }

    /** The key bundle's endorsement of this phone's signing key, if it is for the current bundle. */
    fun endorsement(): ByteArray? {
        if (!state.value.signedAsYours) return null
        return prefs.getString("endorsement", null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
    }

    fun saveEndorsement(keyId: String, endorsement: ByteArray) = update {
        it.putString("endorsement", Base64.encodeToString(endorsement, Base64.NO_WRAP)).putString("endorsedKeyId", keyId)
    }
}
