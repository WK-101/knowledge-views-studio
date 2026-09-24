package app.parley.blocking

import android.content.Context
import app.parley.common.ListMode
import app.parley.common.spam.ListPack
import app.parley.common.spam.PackOrigin
import app.parley.common.templates.ImportedTemplate
import app.parley.common.templates.InstalledTemplate
import app.parley.common.templates.OpenedTemplate
import app.parley.common.templates.RuleTemplate
import app.parley.common.templates.RuleTemplates
import app.parley.common.templates.TemplateGalleryState
import app.parley.data.DataContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Rule-pack templates (v3): curated rule sets shipped as JSON assets, plus templates received from family as
 * signed files or QR codes. Installing one records exactly what it created (rule ids, a warn list, the
 * previous values of the settings it changed), so uninstalling removes the group and nothing else.
 */
class TemplateGallery private constructor(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "blocking/templates.json")
    private val lock = Mutex()
    private val _state = MutableStateFlow(TemplateGalleryState.decode(runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()))
    val state: StateFlow<TemplateGalleryState> = _state.asStateFlow()

    /** A template in the gallery. [fingerprint] is the sender's key for imported ones. */
    data class Entry(val template: RuleTemplate, val builtIn: Boolean, val fingerprint: String? = null)

    val builtIns: List<RuleTemplate> by lazy {
        val names = runCatching { app.assets.list(ASSETS).orEmpty().filter { it.endsWith(".json") }.sorted() }.getOrDefault(emptyList())
        names.mapNotNull { n -> runCatching { RuleTemplates.parse(app.assets.open("$ASSETS/$n").bufferedReader().use { it.readText() }) }.getOrNull() }
    }

    fun entries(s: TemplateGalleryState = _state.value): List<Entry> {
        val builtInIds = builtIns.map { it.id }.toSet()
        val imported = s.imported.mapNotNull { i ->
            runCatching { Entry(RuleTemplates.parse(i.json), false, i.fingerprint) }.getOrNull()?.takeIf { it.template.id !in builtInIds }
        }
        return builtIns.map { Entry(it, true) } + imported
    }

    private suspend fun write(f: (TemplateGalleryState) -> TemplateGalleryState) = withContext(Dispatchers.IO) {
        val s = f(_state.value)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "templates.json.tmp")
        tmp.writeText(s.encode())
        if (!tmp.renameTo(file)) {
            file.writeText(s.encode())
            tmp.delete()
        }
        _state.value = s
    }

    fun installed(id: String): InstalledTemplate? = _state.value.installed.firstOrNull { it.id == id }

    /** Installs [t] as a group. Rules that already exist are left alone and not claimed by the template. */
    suspend fun install(c: DataContainer, t: RuleTemplate): String = lock.withLock {
        if (installed(t.id) != null) uninstallLocked(c, t.id)
        val existing = c.blocks.enabledRules().map { "${it.kind}|${it.type}|${it.pattern.trim()}" }.toHashSet()
        val ruleIds = ArrayList<Long>()
        var skipped = 0
        for (r in RuleTemplates.toRules(t)) {
            if (!existing.add("${r.kind}|${r.type}|${r.pattern}")) {
                skipped++
                continue
            }
            ruleIds += c.blocks.saveRule(r)
        }
        var packId: String? = null
        RuleTemplates.toPack(t)?.let { bytes ->
            val parsed = withContext(Dispatchers.Default) { ListPack.parse(bytes) }
            c.lists.install(parsed, PackOrigin.BUILTIN, force = true)
            packId = parsed.manifest.id
            if (t.warnList?.mode == ListMode.BLOCK) c.lists.setPack(parsed.manifest.id) { it.copy(mode = ListMode.BLOCK) }
        }
        val before = t.settings?.let { patch ->
            val snap = patch.snapshot(c.settings.current().screening)
            c.settings.update { a -> a.copy(screening = patch.apply(a.screening)) }
            snap
        }
        write { s ->
            s.copy(installed = s.installed.filter { it.id != t.id } + InstalledTemplate(t.id, t.name, t.version, ruleIds, packId, before, System.currentTimeMillis()))
        }
        buildString {
            append("Installed ${t.name}")
            if (skipped > 0) append(" ($skipped rules you already had were kept as they are)")
        }
    }

    suspend fun uninstall(c: DataContainer, id: String) = lock.withLock { uninstallLocked(c, id) }

    private suspend fun uninstallLocked(c: DataContainer, id: String) {
        val inst = installed(id) ?: return
        inst.ruleIds.forEach { runCatching { c.blocks.deleteRule(it) } }
        inst.packId?.let { runCatching { c.lists.remove(it) } }
        inst.settingsBefore?.let { before -> c.settings.update { a -> a.copy(screening = before.apply(a.screening)) } }
        write { s -> s.copy(installed = s.installed.filter { it.id != id }) }
    }

    /** Keeps a template received from someone else. Returns a message for the user. */
    suspend fun import(opened: OpenedTemplate): String {
        val t = opened.template
        if (builtIns.any { it.id == t.id }) return "You already have \"${t.name}\": it's built into Parley"
        write { s ->
            s.copy(imported = s.imported.filter { runCatching { RuleTemplates.parse(it.json).id }.getOrNull() != t.id } + ImportedTemplate(opened.json, opened.fingerprint, System.currentTimeMillis()))
        }
        return "Added \"${t.name}\" to your templates"
    }

    suspend fun removeImported(c: DataContainer, id: String) {
        uninstall(c, id)
        write { s -> s.copy(imported = s.imported.filter { runCatching { RuleTemplates.parse(it.json).id }.getOrNull() != id }) }
    }

    companion object {
        private const val ASSETS = "templates"

        // Holds only the application context.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: TemplateGallery? = null

        fun get(context: Context): TemplateGallery = instance ?: synchronized(this) { instance ?: TemplateGallery(context).also { instance = it } }
    }
}

/** A template link opened from a QR scanner, waiting for the gallery screen to show it. */
object TemplateInbox {
    val pending = MutableStateFlow<android.net.Uri?>(null)
}
