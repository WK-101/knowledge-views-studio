package app.parley.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.AppViewModel
import app.parley.R
import app.parley.data.DataArea
import app.parley.data.DataCheckItem
import app.parley.data.DataCheckReport
import app.parley.data.DataSelfCheck
import app.parley.data.DataStatus
import app.parley.ui.Banner
import app.parley.ui.BannerTone
import app.parley.ui.Destination
import app.parley.ui.InfoRow
import app.parley.ui.Routes
import app.parley.ui.SegmentedGroup
import app.parley.ui.SettingsScaffold
import app.parley.ui.Spacing

/**
 * "Check Parley's data" (study SX-4): a read-only self-check that opens each sealed store and database, confirms every
 * record still decrypts, and reports in plain words. It never changes, moves or deletes anything, unlike a
 * competitor's "self-healing" wipe; when something can't be read it only says so and points to Backup's restore.
 */
@Composable
fun CheckDataScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit) {
    val check = remember { DataSelfCheck() }
    var report by remember { mutableStateOf<DataCheckReport?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        report = null
        report = check.run(vm.c)
    }
    SettingsScaffold(stringResource(R.string.label_check_data), back) {
        Text(
            stringResource(R.string.cd_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl),
        )
        val r = report
        if (r == null) {
            CircularProgressIndicator(Modifier.padding(Spacing.xxl))
            return@SettingsScaffold
        }
        if (r.allClear) {
            Banner(stringResource(R.string.cd_all_clear), icon = Icons.Rounded.CheckCircle, tone = BannerTone.INFO)
        } else {
            Banner(
                pluralStringResource(R.plurals.cd_some_unreadable, r.unreadable, r.unreadable),
                icon = Icons.Rounded.ErrorOutline, tone = BannerTone.WARNING,
                action = stringResource(R.string.label_encrypted_backups),
                onAction = { open(Routes.Backup) },
            )
        }
        SegmentedGroup {
            r.items.forEach { row -> item(row.area.name + row.name.orEmpty()) { ResultRow(row) } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.s), horizontalArrangement = Arrangement.End) {
            OutlinedButton({ round++ }) {
                Icon(Icons.Rounded.Refresh, null)
                Text(stringResource(R.string.cd_check_again), Modifier.padding(start = Spacing.s))
            }
        }
    }
}

@Composable
private fun ResultRow(item: DataCheckItem) {
    val cs = MaterialTheme.colorScheme
    val (icon: ImageVector, tint) = when (item.status) {
        DataStatus.OK -> Icons.Rounded.CheckCircle to cs.primary
        DataStatus.EMPTY -> Icons.Rounded.Remove to cs.onSurfaceVariant
        DataStatus.UNREADABLE -> Icons.Rounded.ErrorOutline to cs.error
        DataStatus.NEEDS_BACKUP -> Icons.Rounded.Warning to cs.error
    }
    InfoRow(areaLabel(item), statusLine(item), trailing = { Icon(icon, null, tint = tint) })
}

/** The plain-language name of the area a row reports on. */
@Composable
private fun areaLabel(item: DataCheckItem): String = when (item.area) {
    DataArea.RECORDS -> stringResource(R.string.cd_area_records)
    DataArea.PRIVATE_CONTACTS -> stringResource(R.string.cd_area_private_contacts)
    DataArea.PRIVATE_CALLS -> stringResource(R.string.cd_area_private_calls)
    DataArea.ARCHIVE -> stringResource(R.string.cd_area_archive)
    DataArea.SNAPSHOTS -> stringResource(R.string.cd_area_snapshots)
    DataArea.LINE_KEYS -> stringResource(R.string.cd_area_line_keys)
    DataArea.BACKUP -> stringResource(R.string.cd_area_backup)
    DataArea.SEALED_DOC -> sealedDocLabel(item.name)
}

/** The plain-language name of one sealed-document store, by its folder name (others fall back to a generic label). */
@Composable
private fun sealedDocLabel(name: String?): String = when (name) {
    "case_files" -> stringResource(R.string.cd_area_case_files)
    "to_call" -> stringResource(R.string.cd_area_to_call)
    "menu_memory" -> stringResource(R.string.cd_area_menus)
    "call_round" -> stringResource(R.string.cd_area_call_round)
    else -> stringResource(R.string.cd_area_sealed_other)
}

/** What the area's verdict reads as: how many were checked, that nothing could be read, or that there's nothing yet. */
@Composable
private fun statusLine(item: DataCheckItem): String = when (item.status) {
    DataStatus.OK -> when (item.area) {
        DataArea.LINE_KEYS -> stringResource(R.string.cd_status_working)
        DataArea.BACKUP -> stringResource(R.string.cd_status_backup_ready)
        else -> pluralStringResource(R.plurals.cd_status_ok, item.rows, item.rows)
    }
    DataStatus.EMPTY -> if (item.area == DataArea.LINE_KEYS) stringResource(R.string.cd_status_keys_none) else stringResource(R.string.cd_status_empty)
    DataStatus.UNREADABLE -> pluralStringResource(R.plurals.cd_status_unreadable, item.unreadable.coerceAtLeast(1), item.unreadable.coerceAtLeast(1))
    DataStatus.NEEDS_BACKUP -> stringResource(R.string.cd_status_needs_backup)
}
