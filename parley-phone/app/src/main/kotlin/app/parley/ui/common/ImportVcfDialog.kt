package app.parley.ui.common

import android.net.Uri
import app.parley.common.catching
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.parley.AppViewModel
import app.parley.R
import app.parley.jobs.UserErrorText
import app.parley.jobs.UserJobs
import app.parley.common.ux.BackupNudge
import android.content.res.Resources
import app.parley.common.vcard.ImportReport
import app.parley.common.vcard.ImportGuard
import app.parley.common.qr.ScannedCard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.ui.res.pluralStringResource
import app.parley.ui.Banner
import app.parley.data.AccountRef
import app.parley.ui.ParleyListItem
import app.parley.ui.backup.rememberBackupFirst
import app.parley.ui.people.accountLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.parley.ui.ParleyDialog
import app.parley.ui.export.SealedImportDialog
import app.parley.ui.qr.forgetScannedCard
import app.parley.ui.people.cards.CardArrivalNotes
import app.parley.ui.people.cards.rememberSignedCardText

/**
 * Import a .vcf opened or shared from another app: choose the account, import, show the result. An encrypted vCard
 * asks for its passphrase first ([SealedImportDialog]). A plain file says first what it will leave out
 * ([ImportLeftOut]); [keep] are the flags ticked back on for a scanned card.
 */
@Composable
fun ImportVcfDialog(vm: AppViewModel, uri: Uri, keep: Set<ScannedCard.Flag> = emptySet(), onDone: () -> Unit) {
    var sealed by remember(uri) { mutableStateOf<Boolean?>(null) }
    // The passphrase, checked: the import wipes it, or the dialog when it closes without importing.
    var passphrase by remember(uri) { mutableStateOf<CharArray?>(null) }
    LaunchedEffect(uri) { sealed = vm.c.vcards.isSealed(uri) }
    when {
        // A moment while the file's first bytes are read.
        sealed == null -> Unit
        sealed == true && passphrase == null -> SealedImportDialog(uri, onDismiss = onDone) { passphrase = it }
        else -> ImportIntoDialog(vm, uri, passphrase, keep, onDone)
    }
}

@Composable
private fun ImportIntoDialog(vm: AppViewModel, uri: Uri, passphrase: CharArray?, keep: Set<ScannedCard.Flag>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<String?>(null) }
    // Closed without importing: a scanned card isn't kept for later either (an import still running deletes it).
    DisposableEffect(uri) {
        onDispose {
            if (!running) {
                passphrase?.fill('\u0000')
                vm.c.scope.launch(Dispatchers.IO) { forgetScannedCard(context.applicationContext, uri) }
            }
        }
    }
    LaunchedEffect(uri) { accounts = withContext(Dispatchers.IO) { vm.c.contacts.accounts() } }
    val res = LocalResources.current
    // A large file offers "Back up first?" before the import starts.
    val backupFirst = rememberBackupFirst(vm)
    // The decision waits for the count (the rows can't be tapped before it's in); a count that can't be taken asks.
    val first by rememberFirstLook(vm, uri, encrypted = passphrase != null)

    // A signed card (e.g. someone's My card sent as a file): an update for the contact who has it, or a warning.
    val signed by rememberSignedCardText(vm, uri)

    ParleyDialog(
        // The import runs on as an app job, so the dialog can always be closed.
        onDismissRequest = onDone,
        title = { Text(stringResource(if (result != null) R.string.hist_import_finished else R.string.import_into)) },
        text = {
            Column {
                when {
                    result != null -> Text(result!!)
                    running -> {
                        Text(stringResource(R.string.hist_importing))
                        LinearProgressIndicator(progress = { progress })
                    }
                    else -> {
                        CardArrivalNotes(vm, signed, onOpen = onDone)
                        ImportLeftOut(first?.scan, keep)
                        val known = first?.count
                        if (known == null) LinearProgressIndicator()
                        accounts.forEach { a ->
                            ParleyListItem(
                                headlineContent = { Text(vm.accountLabel(a)) },
                                modifier = Modifier.clickable(enabled = known != null) {
                                    backupFirst.ask(known ?: return@clickable, BackupNudge.LARGE_IMPORT) {
                                        running = true
                                        // An app job: closing the dialog or leaving Parley doesn't stop the import half way.
                                        val job = vm.jobs.start(
                                            UserJobs.Kind.IMPORT, res.getString(R.string.hist_importing),
                                            { e -> res.getString(R.string.csv_import_failed, UserErrorText.of(context, e)).also { result = it } },
                                        ) { p ->
                                            val r = try {
                                                vm.c.vcards.importVCard(
                                                    uri, a,
                                                    { done, total ->
                                                        p.update(done, total)
                                                        progress = if (total > 0) done.toFloat() / total else 0f
                                                    },
                                                    skipDuplicates = true,
                                                    passphrase = passphrase,
                                                    keep = keep,
                                                )
                                            } finally {
                                                passphrase?.fill('\u0000')
                                            }
                                            // A scanned card goes as soon as it has been read.
                                            withContext(Dispatchers.IO) { forgetScannedCard(context.applicationContext, uri) }
                                            importedInto(res, r, a).also { result = it }
                                        }
                                        scope.launch {
                                            job.join()
                                            running = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { if (!running) TextButton(onDone) { Text(stringResource(if (result != null) R.string.dc_done else R.string.dc_cancel)) } },
    )
}

/** "Imported 12 of 14 into …": the account chosen, or the one Android 16 put them in instead. */
private fun importedInto(res: Resources, r: ImportReport, chosen: AccountRef): String =
    r.savedInstead?.let { res.getString(R.string.import_into_account_instead, r.localizedSummary(res), it) }
        ?: res.getString(R.string.import_into_account, r.localizedSummary(res), chosen.displayLabel)

/**
 * Before a plain import: what it leaves out, because anyone can write such a file ([ImportGuard]), and how many cards
 * become private contacts. Nothing when there is neither, or no [scan] yet.
 */
@Composable
fun ImportLeftOut(scan: ImportGuard.Scan?, keep: Set<ScannedCard.Flag> = emptySet()) {
    if (scan == null) return
    val d = scan.dropped.without(keep)
    val lines = buildList {
        if (d.archived > 0) add(pluralStringResource(R.plurals.import_left_out_archived, d.archived, d.archived))
        if (d.starred > 0) add(pluralStringResource(R.plurals.import_left_out_favourite, d.starred, d.starred))
        if (d.voicemail > 0) add(pluralStringResource(R.plurals.import_left_out_voicemail, d.voicemail, d.voicemail))
        if (d.ringtone > 0) add(pluralStringResource(R.plurals.import_left_out_ringtone, d.ringtone, d.ringtone))
        if (d.otherApps > 0) add(pluralStringResource(R.plurals.import_left_out_other_apps, d.otherApps, d.otherApps))
    }
    val text = buildList {
        if (lines.isNotEmpty()) {
            add(stringResource(R.string.import_left_out))
            lines.forEach { add("• $it") } // l10n-ok (bullet)
            add(stringResource(R.string.import_left_out_why))
        }
        if (scan.private > 0) add(pluralStringResource(R.plurals.import_private_cards, scan.private, scan.private))
    }
    if (text.isNotEmpty()) Banner(text.joinToString("\n"), icon = Icons.Rounded.Shield)
}

/** The import's first look at a file: how many contacts (for "Back up first?") and, for a plain file, its [scan]. */
private class FirstLook(val count: Int, val scan: ImportGuard.Scan?)

/**
 * A plain file's bounded first look ([ImportGuard.Scan]), which also says what the import will leave out; null until
 * it is in. An [encrypted] file can't be counted before it is opened, and is Parley's own: no look, no "Back up first?".
 * A count that can't be taken asks.
 */
@Composable
private fun rememberFirstLook(vm: AppViewModel, uri: Uri, encrypted: Boolean) = produceState<FirstLook?>(null, uri, encrypted) {
    value = if (encrypted) {
        FirstLook(0, null)
    } else {
        val scan = catching { vm.c.vcards.preScan(uri) }.getOrNull()
        FirstLook(scan?.let { if (it.capped) BackupNudge.LARGE_IMPORT.coerceAtLeast(it.entries) else it.entries } ?: BackupNudge.LARGE_IMPORT, scan)
    }
}
