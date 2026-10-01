package app.parley.common.backup

/** Where the backup folder lives, judged from its tree URI alone (P13). */
enum class FolderPlace {
    /** No folder chosen. */
    NONE,

    /** The phone's own storage: gone with a lost or broken phone. */
    PHONE_ONLY,

    /** The phone's own storage, in a folder whose name says another device syncs it (Syncthing and the like). */
    PHONE_SYNCED,

    /** A memory card or USB drive: survives a broken phone, not a lost one (the card goes with it). */
    REMOVABLE,

    /** A cloud or server app's storage (Nextcloud, Google Drive…): leaves the phone. */
    CLOUD,

    /** Another app's storage Parley doesn't know: can't tell. */
    UNKNOWN,
}

/** A folder's place, and the app or service holding it when Parley recognises it ("Nextcloud"). */
data class FolderLocation(val place: FolderPlace, val provider: String? = null)

/** The one status line on the Backup screen, most urgent problem first, with the fix it offers. */
enum class BackupStatus { NO_PASSPHRASE, NO_FOLDER, FOLDER_GONE, FAILED, NOT_VERIFIED, NEVER, OVERDUE, PHONE_ONLY, REMOVABLE, OK }

enum class BackupFix { SET_PASSPHRASE, CHOOSE_FOLDER, BACK_UP_NOW, NONE }

/**
 * The Backup setup checker (P13): where the backup folder is (from the tree URI's authority and volume only; no file
 * is ever read), how old the last good backup is and whether it was verified, as one status line with one fix.
 */
object BackupSetupCheck {
    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** Phone-local providers besides shared storage (Downloads, media). */
    private val PHONE_PROVIDERS = setOf("com.android.providers.downloads.documents", "com.android.providers.media.documents")

    /** Cloud and server apps, recognised by a part of their documents provider's authority. Order matters (first wins). */
    private val CLOUD = listOf(
        "nextcloud" to "Nextcloud",
        "owncloud" to "ownCloud",
        "com.google.android.apps.docs" to "Google Drive",
        "dropbox" to "Dropbox",
        "skydrive" to "OneDrive",
        "onedrive" to "OneDrive",
        "seafile" to "Seafile",
        "pcloud" to "pCloud",
        "mega" to "MEGA",
        "proton" to "Proton Drive",
        "com.box." to "Box",
        "syncthing" to "Syncthing",
        "davx5" to "DAVx⁵",
        "bitfire" to "DAVx⁵",
        "cryptomator" to "Cryptomator",
        "filen" to "Filen",
        "koofr" to "Koofr",
        "icedrive" to "Icedrive",
        "tresorit" to "Tresorit",
        "sync.com" to "Sync.com",
    )

    /** Folder names that suggest another device keeps a copy (only a hint: the folder itself is on the phone). */
    private val SYNCED_FOLDER_HINTS = listOf("syncthing", "nextcloud", "owncloud", "foldersync", "dropsync", "autosync", "resilio", "seafile")

    /** Where a `content://authority/tree/docId` folder lives; [treeUri] null means none chosen. */
    fun locate(treeUri: String?): FolderLocation {
        if (treeUri.isNullOrBlank()) return FolderLocation(FolderPlace.NONE)
        val rest = treeUri.substringAfter("content://", "")
        val authority = rest.substringBefore('/').lowercase()
        if (authority.isEmpty()) return FolderLocation(FolderPlace.UNKNOWN)
        val docId = decode(rest.substringAfter("/tree/", "").substringBefore('/'))
        return when {
            authority == EXTERNAL_STORAGE -> {
                val volume = docId.substringBefore(':')
                val path = docId.substringAfter(':', "").lowercase()
                when {
                    // "primary" is the phone's shared storage; "home" is its Documents folder (Android 10+ picker).
                    volume.equals("primary", true) || volume.equals("home", true) || volume.isEmpty() ->
                        if (SYNCED_FOLDER_HINTS.any { it in path }) FolderLocation(FolderPlace.PHONE_SYNCED) else FolderLocation(FolderPlace.PHONE_ONLY)
                    // Any other volume ("1A2B-3C4D") is a memory card or a USB drive.
                    else -> FolderLocation(FolderPlace.REMOVABLE)
                }
            }
            authority in PHONE_PROVIDERS -> FolderLocation(FolderPlace.PHONE_ONLY)
            else -> CLOUD.firstOrNull { (part, _) -> part in authority }?.let { FolderLocation(FolderPlace.CLOUD, it.second) }
                ?: FolderLocation(FolderPlace.UNKNOWN)
        }
    }

    /** The last moment a backup was known good: written, or checked unchanged (both verify the file). */
    fun lastGood(lastBackupAt: Long, lastVerifiedAt: Long): Long = maxOf(lastBackupAt, lastVerifiedAt)

    /**
     * The status line. [lastResult] is the stored result kind ([app.parley.common.StoredStatus]'s kind: "failed",
     * "folder_gone", "not_verified"…); [overdueDays] is the user's backup reminder (14 or 30 days).
     */
    fun status(
        hasPassphrase: Boolean,
        folder: FolderLocation,
        lastBackupAt: Long,
        lastVerifiedAt: Long,
        lastResult: String?,
        now: Long,
        overdueDays: Int,
    ): BackupStatus {
        val good = lastGood(lastBackupAt, lastVerifiedAt)
        return when {
            !hasPassphrase -> BackupStatus.NO_PASSPHRASE
            folder.place == FolderPlace.NONE -> BackupStatus.NO_FOLDER
            lastResult == "folder_gone" -> BackupStatus.FOLDER_GONE
            lastResult == "failed" -> BackupStatus.FAILED
            lastResult == "not_verified" -> BackupStatus.NOT_VERIFIED
            good <= 0 -> BackupStatus.NEVER
            now - good >= overdueDays.coerceAtLeast(1) * DAY_MS -> BackupStatus.OVERDUE
            folder.place == FolderPlace.PHONE_ONLY -> BackupStatus.PHONE_ONLY
            folder.place == FolderPlace.REMOVABLE -> BackupStatus.REMOVABLE
            else -> BackupStatus.OK
        }
    }

    fun fix(status: BackupStatus): BackupFix = when (status) {
        BackupStatus.NO_PASSPHRASE -> BackupFix.SET_PASSPHRASE
        BackupStatus.NO_FOLDER, BackupStatus.FOLDER_GONE, BackupStatus.PHONE_ONLY, BackupStatus.REMOVABLE -> BackupFix.CHOOSE_FOLDER
        BackupStatus.FAILED, BackupStatus.NOT_VERIFIED, BackupStatus.NEVER, BackupStatus.OVERDUE -> BackupFix.BACK_UP_NOW
        BackupStatus.OK -> BackupFix.NONE
    }

    /** Whether the line is a problem (warning colours) rather than a reassurance. */
    fun isProblem(status: BackupStatus): Boolean = status != BackupStatus.OK

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Percent-decoding of a URI path segment (UTF-8); a malformed escape is kept as it is. */
    internal fun decode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                out.write(hex)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
