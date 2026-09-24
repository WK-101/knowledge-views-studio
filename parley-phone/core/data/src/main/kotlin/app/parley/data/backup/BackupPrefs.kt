package app.parley.data.backup

import android.content.Context
import android.util.Base64
import app.parley.common.backup.KeyBundle
import app.parley.common.backup.RetentionPolicy
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
    val lastResult: String? = null,
    val rotationPaused: Boolean = false,
    /** Raw contacts the last restore created (raw, not aggregate ids: a restored entry may have joined an existing contact). */
    val lastRestoreIds: List<Long> = emptyList(),
    /** Newest backup that contains private contacts; rotation keeps it while newer ones lack them. */
    val lastVaultBackupName: String? = null,
) {
    val policy: RetentionPolicy get() = if (keepLast > 0) RetentionPolicy.Simple(keepLast) else RetentionPolicy.Periodic(daily = 7, weekly = 5, monthly = 12, yearly = 3)
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
        lastResult = prefs.getString("lastResult", null),
        rotationPaused = prefs.getBoolean("paused", false),
        lastRestoreIds = prefs.getString("restoreRawIds", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() },
        lastVaultBackupName = prefs.getString("vaultName", null),
    )

    fun update(f: (android.content.SharedPreferences.Editor) -> Unit) {
        prefs.edit().also(f).apply()
        _state.value = load()
    }

    fun keyBundle(): KeyBundle? = prefs.getString("bundle", null)?.let { runCatching { KeyBundle.fromBytes(Base64.decode(it, Base64.NO_WRAP)) }.getOrNull() }

    fun saveKeyBundle(b: KeyBundle) = update {
        it.putString("bundle", Base64.encodeToString(b.toBytes(), Base64.NO_WRAP)).putString("keyId", b.keyId)
    }
}
