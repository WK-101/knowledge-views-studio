package app.parley.common.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSetupCheckTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    @Test fun locatesFoldersByAuthorityAndVolume() {
        fun place(uri: String?) = BackupSetupCheck.locate(uri).place
        assertEquals(FolderPlace.NONE, place(null))
        assertEquals(FolderPlace.PHONE_ONLY, place("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FParley"))
        assertEquals(FolderPlace.PHONE_ONLY, place("content://com.android.externalstorage.documents/tree/home%3AParley"))
        assertEquals(FolderPlace.PHONE_SYNCED, place("content://com.android.externalstorage.documents/tree/primary%3ASyncthing%2FBackups"))
        assertEquals(FolderPlace.REMOVABLE, place("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AParley"))
        assertEquals(FolderPlace.PHONE_ONLY, place("content://com.android.providers.downloads.documents/tree/downloads"))
        assertEquals(FolderPlace.UNKNOWN, place("content://com.example.files/tree/root"))
        assertEquals(FolderPlace.UNKNOWN, place("not a uri"))
    }

    @Test fun recognisesCloudProviders() {
        assertEquals(FolderLocation(FolderPlace.CLOUD, "Nextcloud"), BackupSetupCheck.locate("content://org.nextcloud.documents/tree/12%2FParley"))
        val drive = "content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dabc"
        assertEquals(FolderLocation(FolderPlace.CLOUD, "Google Drive"), BackupSetupCheck.locate(drive))
        assertEquals("ownCloud", BackupSetupCheck.locate("content://org.owncloud.documents/tree/x").provider)
        assertEquals("OneDrive", BackupSetupCheck.locate("content://com.microsoft.skydrive.content.StorageAccessProvider/tree/x").provider)
    }

    @Test fun decodesPercentEscapes() {
        assertEquals("primary:Documents/Parley ü", BackupSetupCheck.decode("primary%3ADocuments%2FParley%20%C3%BC"))
        assertEquals("100%", BackupSetupCheck.decode("100%"))
    }

    private fun status(
        pass: Boolean = true,
        uri: String? = "content://org.nextcloud.documents/tree/1",
        lastAt: Long = now - day,
        verifiedAt: Long = now - day,
        result: String? = "result",
        overdue: Int = 30,
    ) = BackupSetupCheck.status(pass, BackupSetupCheck.locate(uri), lastAt, verifiedAt, result, now, overdue)

    @Test fun mostUrgentProblemFirst() {
        assertEquals(BackupStatus.OK, status())
        assertEquals(BackupStatus.NO_PASSPHRASE, status(pass = false, uri = null))
        assertEquals(BackupStatus.NO_FOLDER, status(uri = null))
        assertEquals(BackupStatus.FOLDER_GONE, status(result = "folder_gone"))
        assertEquals(BackupStatus.FAILED, status(result = "failed"))
        assertEquals(BackupStatus.NOT_VERIFIED, status(result = "not_verified"))
        assertEquals(BackupStatus.NEVER, status(lastAt = 0, verifiedAt = 0, result = null))
        assertEquals(BackupStatus.OVERDUE, status(lastAt = now - 40 * day, verifiedAt = now - 40 * day))
        assertEquals(BackupStatus.OVERDUE, status(lastAt = now - 15 * day, verifiedAt = now - 15 * day, overdue = 14))
        assertEquals(BackupStatus.PHONE_ONLY, status(uri = "content://com.android.externalstorage.documents/tree/primary%3AParley"))
        assertEquals(BackupStatus.REMOVABLE, status(uri = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AParley"))
        assertEquals(BackupStatus.OK, status(uri = "content://com.android.externalstorage.documents/tree/primary%3ASyncthing"))
    }

    @Test fun anUnchangedCheckCountsAsAFreshBackup() {
        // "Nothing changed" verifies the newest file without writing a new one.
        assertEquals(BackupStatus.OK, status(lastAt = now - 60 * day, verifiedAt = now - day, result = "unchanged"))
    }

    @Test fun eachStatusHasItsFix() {
        assertEquals(BackupFix.SET_PASSPHRASE, BackupSetupCheck.fix(BackupStatus.NO_PASSPHRASE))
        assertEquals(BackupFix.CHOOSE_FOLDER, BackupSetupCheck.fix(BackupStatus.PHONE_ONLY))
        assertEquals(BackupFix.CHOOSE_FOLDER, BackupSetupCheck.fix(BackupStatus.FOLDER_GONE))
        assertEquals(BackupFix.BACK_UP_NOW, BackupSetupCheck.fix(BackupStatus.OVERDUE))
        assertEquals(BackupFix.NONE, BackupSetupCheck.fix(BackupStatus.OK))
    }
}
