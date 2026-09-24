package app.parley.ui.blocking

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.blocking.TemplateGallery
import app.parley.blocking.TemplateInbox
import app.parley.blocking.TemplateText
import app.parley.common.templates.OpenedTemplate
import app.parley.common.templates.RuleTemplate
import app.parley.common.templates.RuleTemplates
import app.parley.common.templates.TemplateException
import app.parley.data.DryRun
import app.parley.ui.contact.SecureQr
import app.parley.ui.settings.bidiLtr
import app.parley.ui.settings.settingTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Blocking › Templates (v3): curated offline rule sets that install and uninstall as a group, each previewed
 * with a side-effect-free replay of last week's calls. Templates travel between phones as signed files or QR
 * codes (`parley://template?d=…`), signed with the same personal key as shared lists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val gallery = remember { TemplateGallery.get(context) }
    val gs by gallery.state.collectAsStateWithLifecycle()
    val entries = remember(gs) { gallery.entries(gs) }
    var incoming by remember { mutableStateOf<OpenedTemplate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var shareMine by remember { mutableStateOf(false) }
    var qrFor by remember { mutableStateOf<RuleTemplate?>(null) }
    val pendingLink by TemplateInbox.pending.collectAsStateWithLifecycle()

    LaunchedEffect(pendingLink) {
        val link = pendingLink ?: return@LaunchedEffect
        TemplateInbox.pending.value = null
        try {
            incoming = withContext(Dispatchers.Default) { RuleTemplates.fromLink(link.toString()) }
        } catch (e: TemplateException) {
            error = TemplateText.error(context, e.message)
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { s ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = s.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_FILE) throw TemplateException(res.getString(R.string.blk_tpl_err_file_large))
                        }
                        out.toByteArray().decodeToString()
                    } ?: throw TemplateException(res.getString(R.string.blk_tpl_err_open_file))
                }
                incoming = withContext(Dispatchers.Default) { RuleTemplates.open(text) }
            } catch (e: TemplateException) {
                error = TemplateText.error(context, e.message)
            } catch (e: Exception) {
                error = res.getString(R.string.blk_fail_read_file)
            }
        }
    }

    fun shareFile(t: RuleTemplate) = scope.launch {
        val signed = withContext(Dispatchers.Default) { vm.c.lists.signTemplate(t) }
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.filter { it.name.endsWith("." + RuleTemplates.EXTENSION) }?.forEach { it.delete() }
            File(dir, t.id.replace('.', '-') + "." + RuleTemplates.EXTENSION).apply { writeText(signed) }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, res.getString(R.string.blk_tpl_share_chooser, TemplateText.name(context, t))))
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(settingTitle("templates")) },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.set_back)) } },
            actions = { IconButton({ pickFile.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.FileOpen, stringResource(R.string.blk_tpl_open_file)) } },
        )
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.blk_tpl_header), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.blk_tpl_header_body),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton({ shareMine = true }) { Icon(Icons.Rounded.Share, null); Text(" " + stringResource(R.string.blk_tpl_share_mine)) }
                    }
                }
            }
            val iso = vm.countryIso
            val groups = listOf(
                R.string.blk_tpl_group_country to entries.filter { it.builtIn && it.template.country.equals(iso, true) },
                R.string.blk_tpl_group_anywhere to entries.filter { it.builtIn && it.template.country == null },
                R.string.blk_tpl_group_other to entries.filter { it.builtIn && it.template.country != null && !it.template.country.equals(iso, true) },
                R.string.blk_tpl_group_received to entries.filter { !it.builtIn },
            )
            groups.forEach { (title, list) ->
                if (list.isNotEmpty()) {
                    item(key = "h$title") { app.parley.ui.contact.Section(stringResource(title)) }
                    items(list, key = { "t" + it.template.id }) { e ->
                        TemplateCard(vm, gallery, e, gs.installed.any { it.id == e.template.id }, onShare = { shareFile(e.template) }, onQr = { qrFor = e.template })
                    }
                }
            }
        }
    }

    incoming?.let { op ->
        val t = op.template
        AlertDialog(
            onDismissRequest = { incoming = null },
            title = { Text(stringResource(R.string.blk_tpl_incoming_title, t.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val mine = op.fingerprint == remember { vm.c.lists.shareFingerprint() }
                    Text(stringResource(if (mine) R.string.blk_tpl_signed_by_you else R.string.blk_tpl_signed_by, op.fingerprint), fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.blk_tpl_check_fingerprint), style = MaterialTheme.typography.bodySmall)
                    if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodySmall)
                    val lines = TemplateText.describe(context, t)
                    lines.take(12).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    if (lines.size > 12) Text(pluralStringResource(R.plurals.set_and_more, lines.size - 12, lines.size - 12), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton({
                    scope.launch { vm.toast(gallery.import(op)) }
                    incoming = null
                }) { Text(stringResource(R.string.blk_tpl_add)) }
            },
            dismissButton = { TextButton({ incoming = null }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
    error?.let { e ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(stringResource(R.string.blk_tpl_cant_open)) },
            text = { Text(e) },
            confirmButton = { TextButton({ error = null }) { Text(stringResource(R.string.set_ok)) } },
        )
    }
    qrFor?.let { t -> TemplateQrDialog(vm, t) { qrFor = null } }
    if (shareMine) ShareMyRulesDialog(vm, onDismiss = { shareMine = false }, onFile = { t -> shareMine = false; shareFile(t) }, onQr = { t -> shareMine = false; qrFor = t })
}

private const val MAX_FILE = 1024 * 1024

@Composable
private fun TemplateCard(vm: AppViewModel, gallery: TemplateGallery, e: TemplateGallery.Entry, installed: Boolean, onShare: () -> Unit, onQr: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val t = e.template
    val name = TemplateText.name(context, t)
    val description = TemplateText.description(context, t)
    var open by remember { mutableStateOf(false) }
    var dry by remember { mutableStateOf<DryRun?>(null) }
    var dryBusy by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (installed) {
                Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Text(" " + stringResource(R.string.blk_tpl_installed_badge), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall)
        if (e.fingerprint != null) Text(stringResource(R.string.blk_tpl_from_key, e.fingerprint), style = MaterialTheme.typography.bodySmall)
        if (open) {
            Text(stringResource(R.string.blk_tpl_what_it_does), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            TemplateText.describe(context, t).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (t.notes.isNotBlank()) Text(t.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (t.sources.isNotEmpty()) {
                Text(stringResource(R.string.blk_tpl_sources), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                t.sources.forEach { s ->
                    Text(
                        "${s.title.ifBlank { stringResource(R.string.blk_tpl_source) }}\n${s.url}" + (s.accessed.takeIf { it.isNotBlank() }?.let { " " + stringResource(R.string.blk_tpl_checked, it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (!installed) {
                val d = dry
                when {
                    d != null -> Text(
                        if (d.current.unknown.isEmpty()) stringResource(R.string.blk_tpl_dry_none) else
                            pluralStringResource(R.plurals.blk_tpl_dry_result, d.current.unknown.size, d.added.size, d.current.unknown.size) +
                                if (d.added.isNotEmpty()) " (" + d.added.take(3).joinToString { bidiLtr(app.parley.ui.common.Format.number(it.call.number, vm.countryIso)) } + if (d.added.size > 3) "…)" else ")" else ".",
                        fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> TextButton({
                        scope.launch {
                            dryBusy = true
                            dry = runCatching {
                                val pack = RuleTemplates.toPack(t)?.let { b -> withContext(Dispatchers.Default) { app.parley.common.spam.ListPack.parse(b) } }
                                vm.c.screener.dryRun(
                                    vm.c.callLog.calls.value.orEmpty(), 7,
                                    candidatePack = pack,
                                    candidateRules = RuleTemplates.toRules(t),
                                    candidateSettings = t.settings?.let { patch -> { s: app.parley.common.ScreeningSettings -> patch.apply(s) } },
                                )
                            }.getOrNull()
                            dryBusy = false
                            if (dry == null) vm.toast(res.getString(R.string.blk_tpl_dry_failed))
                        }
                    }, enabled = !dryBusy) { Text(stringResource(if (dryBusy) R.string.blk_tpl_dry_busy else R.string.blk_tpl_dry_button)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (installed) {
                    OutlinedButton({ scope.launch { busy = true; gallery.uninstall(vm.c, t.id); busy = false; vm.toast(res.getString(R.string.blk_tpl_removed, name)) } }, enabled = !busy) { Text(stringResource(R.string.blk_tpl_uninstall)) }
                } else {
                    OutlinedButton({ scope.launch { busy = true; vm.toast(gallery.install(vm.c, t)); busy = false } }, enabled = !busy) { Text(stringResource(R.string.blk_tpl_install)) }
                }
                IconButton(onShare) { Icon(Icons.Rounded.Share, stringResource(R.string.blk_tpl_share_file)) }
                IconButton(onQr) { Icon(Icons.Rounded.QrCode, stringResource(R.string.blk_tpl_share_qr)) }
                if (!e.builtIn) TextButton({ scope.launch { gallery.removeImported(vm.c, t.id) } }) { Text(stringResource(R.string.blk_delete), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun TemplateQrDialog(vm: AppViewModel, t: RuleTemplate, onDismiss: () -> Unit) {
    val result by produceState<Pair<Bitmap?, Boolean>?>(null, t) {
        value = withContext(Dispatchers.Default) {
            val link = RuleTemplates.toLink(vm.c.lists.signTemplate(t))
            if (RuleTemplates.fitsInQr(link)) SecureQr.qr(link) to true else null to false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(TemplateText.name(LocalContext.current, t)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val r = result
                when {
                    r == null -> Text(stringResource(R.string.blk_preparing))
                    !r.second -> Text(stringResource(R.string.blk_tpl_qr_too_large))
                    r.first != null -> Image(r.first!!.asImageBitmap(), stringResource(R.string.blk_tpl_qr_cd), Modifier.size(260.dp).background(Color.White).padding(8.dp))
                }
                Text(
                    stringResource(R.string.blk_tpl_qr_help, remember { vm.c.lists.shareFingerprint() }),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.set_done)) } },
    )
}

@Composable
private fun ShareMyRulesDialog(vm: AppViewModel, onDismiss: () -> Unit, onFile: (RuleTemplate) -> Unit, onQr: (RuleTemplate) -> Unit) {
    val rules by vm.c.blocks.rules.collectAsStateWithLifecycle()
    val defaultName = stringResource(R.string.blk_tpl_my_rules)
    var name by remember { mutableStateOf(defaultName) }
    val template = remember(rules, name) {
        RuleTemplates.fromRules("shared.r" + (System.currentTimeMillis() / 1000), name.trim().ifBlank { defaultName }.take(120), "", rules, System.currentTimeMillis() / 1000)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.blk_tpl_share_my_rules)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.blk_tpl_name)) }, singleLine = true)
                Text(
                    pluralStringResource(R.plurals.blk_tpl_share_count, template.rules.size, template.rules.size),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton({ onQr(template) }, enabled = template.rules.isNotEmpty()) { Text(stringResource(R.string.blk_qr_code)) }
                TextButton({ onFile(template) }, enabled = template.rules.isNotEmpty()) { Text(stringResource(R.string.blk_file)) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.set_cancel)) } },
    )
}
