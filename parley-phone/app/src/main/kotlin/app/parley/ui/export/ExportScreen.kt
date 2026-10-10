package app.parley.ui.export

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.security.PassphraseStrength
import app.parley.data.export.ContactExport
import app.parley.data.export.ContactExport.Format
import app.parley.data.vault.VaultCrypto
import app.parley.jobs.UserErrorText
import app.parley.jobs.UserJobs
import app.parley.security.AppLock
import app.parley.ui.Banner
import app.parley.ui.BannerTone
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyTopBar
import app.parley.ui.Section
import app.parley.ui.StrengthMeter
import app.parley.ui.SwitchRow
import app.parley.ui.backup.PassField
import app.parley.ui.backup.strengthHint
import app.parley.ui.backup.strengthLabel
import app.parley.ui.common.JobProgress
import app.parley.ui.settings.exportMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Opens the system's "Save as" for a name and type chosen at launch (the format decides both). */
private class SaveAs : ActivityResultContract<Format, Uri?>() {
    override fun createIntent(context: Context, input: Format): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.mime).putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}

/**
 * Settings › Contacts › Export contacts (and Backup & sync › Export contacts and notes): every contact in an open
 * format, private contacts only when asked (with a plain warning that a plain file isn't encrypted), Parley's notes
 * with them, or an encrypted vCard locked with a passphrase. The export runs as an app job: leaving the screen, or
 * Parley, doesn't stop it half way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("CyclomaticComplexMethod") // One screen of choices: each row shows or enables by the format chosen.
@Composable
fun ExportScreen(vm: AppViewModel, initial: String?, back: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    var format by rememberSaveable { mutableStateOf(Format.entries.firstOrNull { it.name == initial } ?: Format.VCARD) }
    var includePrivate by rememberSaveable { mutableStateOf(false) }
    var includeNotes by rememberSaveable { mutableStateOf(true) }
    // The passphrase lives only in memory (never in saved state), through rotation and the "Save as" picker.
    val secrets: ExportPassphrase = viewModel()
    val pass = secrets.pass
    val repeat = secrets.repeat
    val jobs by vm.jobs.running.collectAsStateWithLifecycle()
    val running = jobs.any { it.kind == UserJobs.Kind.EXPORT }
    val hasPrivate = vm.c.vault.contacts.collectAsStateWithLifecycle().value.isNotEmpty() && !vm.privacy.collectAsStateWithLifecycle().value.hiding
    val sealed = format == Format.SEALED_VCARD
    // The text file is the notes: they are always in it.
    val notesOn = format == Format.NOTES_TEXT || (includeNotes && format.carriesNotes)
    val passOk = !sealed || (PassphraseStrength.acceptableForBackup(pass) && pass == repeat)
    val withPrivate = includePrivate && hasPrivate

    // A file "Save as" created that won't be written is removed (off the main thread: it asks the file's provider).
    fun discard(uri: Uri) {
        val app = context.applicationContext
        vm.c.scope.launch(Dispatchers.IO) { ContactExport.discard(app, uri) }
    }

    fun start(uri: Uri) {
        val choice = ContactExport.Choice(format, includePrivate = withPrivate, includeNotes = notesOn)
        val secret = if (sealed) secrets.take() else null
        if (sealed && (secret == null || secret.isEmpty())) {
            // Parley was closed while "Save as" was open, and the passphrase went with it: the empty file goes too.
            secret?.fill('\u0000')
            discard(uri)
            vm.toast(res.getString(R.string.export_pass_again))
            return
        }
        secrets.clear()
        val words = ExportWords.build(context)
        vm.jobs.start(
            UserJobs.Kind.EXPORT, res.getString(R.string.set_exporting),
            { e -> res.getString(R.string.hist_export_failed, UserErrorText.of(context, e)) },
            output = uri.toString(),
        ) { p ->
            try {
                exportMessage(context, vm.c.contactExport.export(uri, choice, words, secret) { done, total -> p.update(done, total) })
            } finally {
                secret?.fill('\u0000')
            }
        }
    }

    val saveAs = rememberLauncherForActivityResult(SaveAs()) { uri ->
        val act = context as? ComponentActivity
        when {
            uri == null -> secrets.release()
            // Private contacts are read only after the unlock that opens them on their page.
            withPrivate && act != null && VaultCrypto.detailNeedsUnlock() -> AppLock.authenticateForVault(act) { ok ->
                if (ok) {
                    start(uri)
                } else {
                    secrets.release()
                    discard(uri)
                    vm.toast(res.getString(R.string.export_private_locked))
                }
            }
            else -> start(uri)
        }
    }

    ParleyScaffold(topBar = { ParleyTopBar(stringResource(R.string.export_title), onBack = back) }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item { JobProgress(vm, UserJobs.Kind.EXPORT) }
            item { Section(stringResource(R.string.export_format)) }
            item { FormatChoice(format) { format = it } }
            item { Section(stringResource(R.string.export_contents)) }
            if (hasPrivate) item { PrivateChoice(includePrivate, warn = !sealed) { includePrivate = it } }
            item {
                SwitchRow(
                    stringResource(R.string.export_notes),
                    stringResource(if (format.carriesNotes) R.string.export_notes_summary else R.string.export_notes_csv),
                    notesOn, Icons.Rounded.Description, enabled = format.carriesNotes && format != Format.NOTES_TEXT,
                ) { includeNotes = it }
            }
            if (sealed) item { PassphraseFields(pass, repeat, { secrets.pass = it }) { secrets.repeat = it } }
            item {
                Button({
                    // Checked again here, and held until "Save as" answers, so the file chosen can always be written.
                    if (passOk) {
                        if (sealed) secrets.hold()
                        saveAs.launch(format)
                    }
                }, enabled = !running && passOk, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.export_save))
                }
            }
            if (format.csv != null) item {
                Text(
                    stringResource(R.string.set_csv_format_formula_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * The passphrase of an encrypted export, in memory only: the fields keep it through rotation, and [hold] takes the copy
 * the export uses when "Save as" opens, so the answer can arrive on a recreated screen. Never in saved state, so it is
 * gone if Android closes Parley meanwhile; every copy is wiped once used, released or the screen is left.
 */
class ExportPassphrase : ViewModel() {
    var pass by mutableStateOf("")
    var repeat by mutableStateOf("")
    private var held: CharArray? = null

    /** Keeps a copy of the passphrase for the export about to be saved. */
    fun hold() {
        release()
        held = pass.toCharArray()
    }

    /** The copy [hold] kept, now the caller's to wipe; null when there is none. */
    fun take(): CharArray? = held.also { held = null }

    /** Wipes the copy (the picker was cancelled, or the export won't happen). */
    fun release() {
        held?.fill('\u0000')
        held = null
    }

    /** Empties the fields once the export has started. */
    fun clear() {
        pass = ""
        repeat = ""
    }

    override fun onCleared() = release()
}

/** The formats, one radio group. */
@Composable
private fun FormatChoice(format: Format, onPick: (Format) -> Unit) {
    Column(Modifier.selectableGroup()) {
        Format.entries.forEach { f ->
            val (title, summary) = formatText(f)
            ParleyListItem(
                modifier = Modifier.selectable(format == f, role = Role.RadioButton) { onPick(f) },
                leadingContent = { RadioButton(format == f, onClick = null) },
                headlineContent = { Text(stringResource(title)) },
                supportingContent = { Text(stringResource(summary)) },
            )
        }
    }
}

/** "Include private contacts", with the warning a plain file needs ([warn]), said before the file exists. */
@Composable
private fun PrivateChoice(on: Boolean, warn: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        SwitchRow(stringResource(R.string.export_private), stringResource(R.string.export_private_summary), on, Icons.Rounded.Lock, onChange = onChange)
        if (on && warn) Banner(stringResource(R.string.export_private_warning), tone = BannerTone.WARNING, icon = Icons.Rounded.Warning)
    }
}

@Composable
private fun PassphraseFields(pass: String, repeat: String, onPass: (String) -> Unit, onRepeat: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PassField(stringResource(R.string.export_pass), pass, onPass)
        if (pass.isNotEmpty()) {
            val estimate = remember(pass) { PassphraseStrength.estimate(pass) }
            StrengthMeter(estimate.score, stringResource(strengthLabel(estimate.score)), strengthHint(estimate.hint)?.let { stringResource(it) })
        }
        PassField(stringResource(R.string.bkp_repeat), repeat, onRepeat)
        Text(stringResource(R.string.export_pass_hint), style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatText(f: Format): Pair<Int, Int> = when (f) {
    Format.VCARD -> R.string.export_vcard to R.string.export_vcard_summary
    Format.SEALED_VCARD -> R.string.export_sealed to R.string.export_sealed_summary
    Format.CSV_PARLEY -> R.string.set_csv_format_parley to R.string.set_csv_format_parley_summary
    Format.CSV_GOOGLE -> R.string.set_csv_format_google to R.string.set_csv_format_google_summary
    Format.CSV_OUTLOOK -> R.string.set_csv_format_outlook to R.string.set_csv_format_outlook_summary
    Format.NOTES_TEXT -> R.string.export_notes_text to R.string.export_notes_text_summary
}
