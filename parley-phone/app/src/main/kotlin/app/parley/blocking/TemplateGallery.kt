package app.parley.blocking

import android.content.Context
import app.parley.R
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
        val name = TemplateText.name(app, t)
        if (skipped > 0) app.resources.getQuantityString(R.plurals.blk_tpl_installed_skipped, skipped, name, skipped)
        else app.getString(R.string.blk_tpl_installed, name)
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
        if (builtIns.any { it.id == t.id }) return app.getString(R.string.blk_tpl_already_built_in, TemplateText.name(app, t))
        write { s ->
            s.copy(imported = s.imported.filter { runCatching { RuleTemplates.parse(it.json).id }.getOrNull() != t.id } + ImportedTemplate(opened.json, opened.fingerprint, System.currentTimeMillis()))
        }
        return app.getString(R.string.blk_tpl_added, t.name)
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

/**
 * Display texts of the built-in templates in the app's language, keyed by template id. The JSON assets stay the
 * source of truth for the rules; imported templates show their own name and description.
 */
object TemplateText {
    private val texts: Map<String, Pair<Int, Int>> = mapOf(
        "de.premium-warn" to (R.string.blk_tpl_de_premium_name to R.string.blk_tpl_de_premium_desc),
        "es.commercial-400" to (R.string.blk_tpl_es_400_name to R.string.blk_tpl_es_400_desc),
        "fr.arcep-telemarketing" to (R.string.blk_tpl_fr_arcep_name to R.string.blk_tpl_fr_arcep_desc),
        "general.contacts-at-night" to (R.string.blk_tpl_night_name to R.string.blk_tpl_night_desc),
        "general.foreign-except-mine" to (R.string.blk_tpl_foreign_name to R.string.blk_tpl_foreign_desc),
        "general.silence-invalid" to (R.string.blk_tpl_invalid_name to R.string.blk_tpl_invalid_desc),
        "in.trai-140" to (R.string.blk_tpl_in_140_name to R.string.blk_tpl_in_140_desc),
        "it.agcom-0843-0844" to (R.string.blk_tpl_it_agcom_name to R.string.blk_tpl_it_agcom_desc),
        "uk.premium-personal-warn" to (R.string.blk_tpl_uk_premium_name to R.string.blk_tpl_uk_premium_desc),
        "us.toll-free-warn" to (R.string.blk_tpl_us_toll_free_name to R.string.blk_tpl_us_toll_free_desc),
    )

    fun name(context: Context, t: app.parley.common.templates.RuleTemplate): String = texts[t.id]?.let { context.getString(it.first) } ?: t.name

    fun description(context: Context, t: app.parley.common.templates.RuleTemplate): String = texts[t.id]?.let { context.getString(it.second) } ?: t.description

    /** [RuleTemplates.describe] in the app's language: one line per rule, range and setting. */
    fun describe(context: Context, t: app.parley.common.templates.RuleTemplate): List<String> {
        val out = ArrayList<String>()
        val german = context.resources.configuration.locales.get(0)?.language == "de"
        RuleTemplates.toRules(t).forEach { r ->
            val what = when (r.type) {
                app.parley.common.RuleType.PREFIX -> context.getString(R.string.blk_tpl_what_prefix, r.pattern)
                app.parley.common.RuleType.EXACT -> app.parley.ui.settings.bidiLtr(r.pattern)
                app.parley.common.RuleType.WILDCARD -> context.getString(R.string.blk_tpl_what_wildcard, r.pattern)
                // German capitalises nouns; elsewhere the title reads as part of the sentence.
                else -> BlockingText.ruleTitle(context, r).let { if (german) it else it.replaceFirstChar { c -> c.lowercase() } }
            }
            val verb = when {
                r.kind == app.parley.common.RuleKind.ALLOW -> R.string.blk_tpl_allow
                r.action == app.parley.common.BlockAction.SILENCE -> R.string.blk_tpl_silence
                else -> R.string.blk_tpl_reject
            }
            out += context.getString(verb, what) +
                (r.schedule?.let { s -> " (" + app.parley.common.Schedule.hm(s.startMinute) + "–" + app.parley.common.Schedule.hm(s.endMinute) + ")" } ?: "") +
                (r.note?.let { " · $it" } ?: "")
        }
        t.warnList?.let { l ->
            val verb = if (l.mode == app.parley.common.ListMode.BLOCK) R.string.blk_tpl_range_block else R.string.blk_tpl_range_warn
            l.ranges.forEach { r ->
                out += context.getString(verb, app.parley.ui.settings.bidiLtr(r.prefix + "…"), l.categories[r.category.toString()] ?: context.getString(R.string.blk_res_listed))
            }
        }
        t.settings?.let { s ->
            fun action(a: app.parley.common.BlockAction) =
                context.getString(if (a == app.parley.common.BlockAction.SILENCE) R.string.blk_tpl_silenced else R.string.blk_tpl_rejected)
            s.blockInvalid?.let {
                out += if (it) context.getString(R.string.blk_tpl_stop_invalid) + (s.invalidAction?.let { a -> " (${action(a)})" } ?: "")
                else context.getString(R.string.blk_tpl_let_invalid)
            }
            s.blockFailedVerification?.let { out += context.getString(if (it) R.string.blk_tpl_stop_verification else R.string.blk_tpl_let_verification) }
            s.offHours?.let { o ->
                out += if (o.enabled) {
                    val who = when (o.allow) {
                        app.parley.common.OffHoursAllow.CONTACTS -> context.getString(R.string.blk_who_contacts)
                        app.parley.common.OffHoursAllow.FAVOURITES -> context.getString(R.string.blk_who_favourites)
                        app.parley.common.OffHoursAllow.LABEL -> o.labelTitle ?: context.getString(R.string.blk_who_label)
                    }
                    context.getString(
                        R.string.blk_tpl_off_hours,
                        app.parley.common.Schedule.hm(o.schedule.startMinute), app.parley.common.Schedule.hm(o.schedule.endMinute), who, action(o.action),
                    )
                } else {
                    context.getString(R.string.blk_tpl_off_hours_off)
                }
            }
        }
        return out
    }

    private val errors = mapOf(
        "This isn't a Parley template" to R.string.blk_tpl_err_not_template,
        "This template needs a newer version of Parley" to R.string.blk_tpl_err_newer,
        "The template has an invalid id" to R.string.blk_tpl_err_id,
        "The template has no name" to R.string.blk_tpl_err_name,
        "The template has too many rules" to R.string.blk_tpl_err_too_many,
        "The template is empty" to R.string.blk_tpl_err_empty,
        "Templates can't contain label rules" to R.string.blk_tpl_err_label,
        "A rule in the template is too long" to R.string.blk_tpl_err_rule_long,
        "A rule in the template has no pattern" to R.string.blk_tpl_err_no_pattern,
        "The template's number list is empty or too long" to R.string.blk_tpl_err_list,
        "The template's score is out of range" to R.string.blk_tpl_err_score,
        "Template sources must be https links" to R.string.blk_tpl_err_sources,
        "This isn't a Parley template file" to R.string.blk_tpl_err_not_file,
        "The template's signature is missing" to R.string.blk_tpl_err_no_signature,
        "The template's signature is not valid: it was changed after signing" to R.string.blk_tpl_err_signature,
        "The code is damaged" to R.string.blk_tpl_err_damaged,
        "The code is too large" to R.string.blk_tpl_err_code_large,
    )
    private val invalidRange = Regex("^The template has an invalid range: (.*)$")

    /** A [app.parley.common.templates.TemplateException] message in the app's language. */
    fun error(context: Context, message: String?): String {
        val m = message ?: return context.getString(R.string.blk_fail_read_file)
        errors[m]?.let { return context.getString(it) }
        invalidRange.matchEntire(m)?.let { return context.getString(R.string.blk_tpl_err_range, it.groupValues[1]) }
        return m
    }
}

/** A template link opened from a QR scanner, waiting for the gallery screen to show it. */
object TemplateInbox {
    val pending = MutableStateFlow<android.net.Uri?>(null)
}
