package app.parley.ui.backup

import android.content.res.Resources
import android.text.format.DateUtils
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.common.StoredStatus
import app.parley.common.backup.BackupFix
import app.parley.common.backup.BackupSetupCheck
import app.parley.common.backup.BackupStatus
import app.parley.common.backup.FolderLocation
import app.parley.common.backup.FolderPlace
import app.parley.data.backup.BackupState
import app.parley.ui.CallColors
import app.parley.ui.ParleyListItem

/** Where the backup folder is, as the folder row's second half ("Nextcloud › Parley · In Nextcloud"). */
fun folderPlaceText(res: Resources, where: FolderLocation): String? = when (where.place) {
    FolderPlace.NONE -> null
    FolderPlace.PHONE_ONLY -> res.getString(R.string.bkp_place_phone)
    FolderPlace.PHONE_SYNCED -> res.getString(R.string.bkp_place_phone_synced)
    FolderPlace.REMOVABLE -> res.getString(R.string.bkp_place_removable)
    FolderPlace.CLOUD -> res.getString(R.string.bkp_place_cloud, where.provider.orEmpty())
    FolderPlace.UNKNOWN -> res.getString(R.string.bkp_place_unknown)
}

/**
 * The Backup setup checker: one status line at the top of the Backup screen, the most urgent thing first
 * (passphrase, folder, a failed or unchecked backup, age, then where the folder lives), with the one fix it needs.
 * The folder is judged by its location alone; no file in it is read.
 */
@Composable
fun BackupSetupStatus(state: BackupState, overdueDays: Int, onFix: (BackupFix) -> Unit) {
    val res = LocalResources.current
    val where = BackupSetupCheck.locate(state.folderUri)
    val now = System.currentTimeMillis()
    val status = BackupSetupCheck.status(
        hasPassphrase = state.hasKeys, folder = where, lastBackupAt = state.lastBackupAt, lastVerifiedAt = state.lastVerifiedAt,
        lastResult = StoredStatus.decode(state.lastResult)?.kind, now = now, overdueDays = overdueDays,
    )
    val good = BackupSetupCheck.lastGood(state.lastBackupAt, state.lastVerifiedAt)
    val ago = DateUtils.getRelativeTimeSpanString(good, now, DateUtils.MINUTE_IN_MILLIS).toString()
    val (title, body) = statusText(res, status, ago, where)
    val fix = BackupSetupCheck.fix(status)
    val problem = BackupSetupCheck.isProblem(status)
    ParleyListItem(
        leadingContent = {
            if (problem) Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
            else Icon(Icons.Rounded.CheckCircle, null, tint = CallColors.Accept)
        },
        headlineContent = { Text(title) },
        supportingContent = body?.let { { Text(it) } },
        trailingContent = fixLabel(status, fix)?.let { label -> { TextButton({ onFix(fix) }) { Text(label) } } },
    )
}

/** The line's title and its one-sentence reason (none when all is well). */
private fun statusText(res: Resources, status: BackupStatus, ago: String, where: FolderLocation): Pair<String, String?> = when (status) {
    BackupStatus.NO_PASSPHRASE -> res.getString(R.string.bkp_check_no_pass) to res.getString(R.string.bkp_check_no_pass_body)
    BackupStatus.NO_FOLDER -> res.getString(R.string.bkp_check_no_folder) to res.getString(R.string.bkp_check_no_folder_body)
    BackupStatus.FOLDER_GONE -> res.getString(R.string.bkp_check_folder_gone) to res.getString(R.string.bkp_check_folder_gone_body)
    BackupStatus.FAILED -> res.getString(R.string.bkp_check_failed) to res.getString(R.string.bkp_check_retry_body)
    BackupStatus.NOT_VERIFIED -> res.getString(R.string.bkp_check_not_verified) to res.getString(R.string.bkp_check_retry_body)
    BackupStatus.NEVER -> res.getString(R.string.bkp_check_never) to res.getString(R.string.bkp_check_never_body)
    BackupStatus.OVERDUE -> res.getString(R.string.bkp_check_overdue, ago) to res.getString(R.string.bkp_check_overdue_body)
    BackupStatus.PHONE_ONLY -> res.getString(R.string.bkp_check_phone_only) to res.getString(R.string.bkp_check_phone_only_body)
    BackupStatus.REMOVABLE -> res.getString(R.string.bkp_check_removable) to res.getString(R.string.bkp_check_removable_body)
    BackupStatus.OK -> {
        val place = folderPlaceText(res, where)?.takeIf { where.place != FolderPlace.UNKNOWN }
        (place?.let { res.getString(R.string.bkp_check_ok_where, ago, it) } ?: res.getString(R.string.bkp_check_ok, ago)) to null
    }
}

@Composable
private fun fixLabel(status: BackupStatus, fix: BackupFix): String? = when (fix) {
    BackupFix.SET_PASSPHRASE -> stringResource(R.string.bkp_fix_pass)
    BackupFix.CHOOSE_FOLDER -> stringResource(
        if (status == BackupStatus.PHONE_ONLY || status == BackupStatus.REMOVABLE) R.string.bkp_fix_other_folder else R.string.bkp_fix_folder,
    )
    BackupFix.BACK_UP_NOW -> stringResource(R.string.bkp_fix_now)
    BackupFix.NONE -> null
}
