package app.parley.common.templates

import app.parley.common.BlockAction
import app.parley.common.BlockRule
import app.parley.common.CountryCodes
import app.parley.common.ListMode
import app.parley.common.OffHours
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.Schedule
import app.parley.common.ScreeningSettings
import app.parley.common.spam.Ed25519
import app.parley.common.spam.PackBuilder
import app.parley.common.spam.PackManifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A rule-pack template (v3): a curated, offline set of screening rules that installs and uninstalls as a group.
 * Templates ship as JSON assets and can be shared between phones as signed files or QR codes.
 *
 * A template can hold three kinds of change:
 * - [rules]: ordinary block or allow rules;
 * - [warnList]: number ranges installed as a local spam list that warns ("Likely spam") by default;
 * - [settings]: a few screening switches (off hours, invalid numbers, failed verification).
 */
@Serializable
data class RuleTemplate(
    val format: Int = 1,
    val id: String,
    val name: String,
    val description: String = "",
    /** ISO country this template is meant for (null: anywhere). */
    val country: String? = null,
    val version: Long = 1,
    val author: String = "",
    /** Where the numbers come from (regulator pages), with the date they were checked. */
    val sources: List<TemplateSource> = emptyList(),
    /** Caveats shown before installing. */
    val notes: String = "",
    val rules: List<TemplateRule> = emptyList(),
    val warnList: TemplateList? = null,
    val settings: TemplateSettings? = null,
)

@Serializable
data class TemplateSource(val url: String, val title: String = "", val accessed: String = "")

@Serializable
data class TemplateRule(
    val kind: RuleKind = RuleKind.BLOCK,
    val type: RuleType,
    val pattern: String = "",
    val action: BlockAction = BlockAction.SILENCE,
    val note: String? = null,
    val schedule: Schedule? = null,
)

@Serializable
data class TemplateRange(val prefix: String, val category: Int = 1, val score: Int? = null)

/** Ranges installed as a spam list named after the template. */
@Serializable
data class TemplateList(
    val ranges: List<TemplateRange>,
    /** Category id (as string) → name. */
    val categories: Map<String, String> = mapOf("1" to "Listed range"),
    val score: Int = 60,
    val mode: ListMode = ListMode.WARN,
)

/** Screening switches a template may change. Null fields are left alone. */
@Serializable
data class TemplateSettings(
    val blockInvalid: Boolean? = null,
    val invalidAction: BlockAction? = null,
    val blockFailedVerification: Boolean? = null,
    val offHours: OffHours? = null,
) {
    fun apply(s: ScreeningSettings): ScreeningSettings = s.copy(
        blockInvalid = blockInvalid ?: s.blockInvalid,
        invalidAction = invalidAction ?: s.invalidAction,
        blockFailedVerification = blockFailedVerification ?: s.blockFailedVerification,
        offHours = offHours ?: s.offHours,
    )

    /** The current values of the fields this template changes, so uninstalling can put them back. */
    fun snapshot(s: ScreeningSettings) = TemplateSettings(
        blockInvalid = blockInvalid?.let { s.blockInvalid },
        invalidAction = invalidAction?.let { s.invalidAction },
        blockFailedVerification = blockFailedVerification?.let { s.blockFailedVerification },
        offHours = offHours?.let { s.offHours },
    )

    fun describe(): List<String> = listOfNotNull(
        blockInvalid?.let { if (it) "Stop numbers that can't exist" + (invalidAction?.let { a -> " (${a.label()})" } ?: "") else "Let invalid numbers ring" },
        blockFailedVerification?.let { if (it) "Stop calls that fail caller verification" else "Let calls that fail verification ring" },
        offHours?.let { o ->
            if (o.enabled) "Off hours ${fmt(o.schedule.startMinute)}–${fmt(o.schedule.endMinute)}: only ${o.allow.name.lowercase()} ring, others ${o.action.label()}" else "Off hours turned off"
        },
    )

    private fun fmt(m: Int) = "%02d:%02d".format(m / 60 % 24, m % 60) // locale-ok: shown to the user
}

private fun BlockAction.label() = if (this == BlockAction.SILENCE) "silenced" else "rejected"

class TemplateException(message: String) : Exception(message)

/** A shared template after its signature was checked. */
data class OpenedTemplate(val template: RuleTemplate, val fingerprint: String, val json: String)

/** The file / QR envelope: the template JSON as text plus an Ed25519 signature over its UTF-8 bytes. */
@Serializable
data class SignedTemplate(val format: Int = 1, val template: String, val publicKey: String, val signature: String)

@Serializable
data class InstalledTemplate(
    val id: String,
    val name: String,
    val version: Long = 1,
    /** Ids of the rules the template created (rules that already existed are not owned by it). */
    val ruleIds: List<Long> = emptyList(),
    val packId: String? = null,
    /** Values of the settings before install, restored on uninstall. */
    val settingsBefore: TemplateSettings? = null,
    val installedAt: Long = 0,
)

@Serializable
data class ImportedTemplate(val json: String, val fingerprint: String, val importedAt: Long = 0)

/** What the gallery keeps: installed templates (with what they own) and templates received from others. */
@Serializable
data class TemplateGalleryState(
    val installed: List<InstalledTemplate> = emptyList(),
    val imported: List<ImportedTemplate> = emptyList(),
) {
    fun encode(): String = CODEC.encodeToString(serializer(), this)

    companion object {
        private val CODEC = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(s: String?): TemplateGalleryState =
            if (s.isNullOrBlank()) TemplateGalleryState() else runCatching { CODEC.decodeFromString(serializer(), s) }.getOrDefault(TemplateGalleryState())
    }
}

object RuleTemplates {
    const val EXTENSION = "parleytemplate"
    const val LINK_PREFIX = "parley://template?d="

    /** Longest link we put in a QR code (byte mode, error correction L holds ~2,900 bytes). */
    const val MAX_QR_LINK = 2_600
    private const val MAX_RULES = 200
    private const val MAX_RANGES = 500
    private val ID = Regex("[a-z0-9][a-z0-9._-]{0,59}")

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = false }
    private val pretty = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

    /** Parses and validates a template. Throws [TemplateException] with a readable reason. */
    fun parse(text: String): RuleTemplate {
        val t = try {
            json.decodeFromString(RuleTemplate.serializer(), text)
        } catch (e: Exception) {
            throw TemplateException("This isn't a Parley template")
        }
        validate(t)
        return t
    }

    fun validate(t: RuleTemplate) {
        if (t.format != 1) throw TemplateException("This template needs a newer version of Parley")
        if (!ID.matches(t.id)) throw TemplateException("The template has an invalid id")
        if (t.name.isBlank() || t.name.length > 120) throw TemplateException("The template has no name")
        if (t.rules.size > MAX_RULES) throw TemplateException("The template has too many rules")
        if (t.rules.isEmpty() && t.warnList == null && t.settings == null) throw TemplateException("The template is empty")
        t.rules.forEach { r ->
            // Label ids are local to one phone, so they can't travel in a template.
            if (r.type == RuleType.LABEL) throw TemplateException("Templates can't contain label rules")
            if (r.pattern.length > 200) throw TemplateException("A rule in the template is too long")
            val needsPattern = r.type != RuleType.NOT_MY_REGION
            if (needsPattern && r.pattern.isBlank()) throw TemplateException("A rule in the template has no pattern")
        }
        t.warnList?.let { l ->
            if (l.ranges.isEmpty() || l.ranges.size > MAX_RANGES) throw TemplateException("The template's number list is empty or too long")
            l.ranges.forEach { r ->
                val p = r.prefix
                if (!p.startsWith("+") || p.length < 3 || !p.drop(1).all { it.isDigit() } || CountryCodes.callingCodeOf(p) == null) {
                    throw TemplateException("The template has an invalid range: $p")
                }
            }
            if (l.score !in 0..100) throw TemplateException("The template's score is out of range")
        }
        t.sources.forEach { s -> if (!s.url.startsWith("https://")) throw TemplateException("Template sources must be https links") }
    }

    fun encode(t: RuleTemplate, prettyPrint: Boolean = false): String =
        (if (prettyPrint) pretty else json).encodeToString(RuleTemplate.serializer(), t)

    /** The rules the template creates, ready to save. */
    fun toRules(t: RuleTemplate): List<BlockRule> = t.rules.map { r ->
        BlockRule(
            pattern = r.pattern.trim(),
            type = r.type,
            action = r.action,
            kind = r.kind,
            schedule = r.schedule,
            note = r.note?.takeIf { it.isNotBlank() },
        )
    }

    fun packId(t: RuleTemplate) = "template.${t.id}"

    /** The warn list as an unsigned pack (it is built on the phone from a template the user chose). */
    fun toPack(t: RuleTemplate, now: Long = System.currentTimeMillis()): ByteArray? {
        val l = t.warnList ?: return null
        val b = PackBuilder(
            PackManifest(
                id = packId(t), name = t.name, publisher = "Template" + (t.author.takeIf { it.isNotBlank() }?.let { " by $it" } ?: ""),
                source = t.sources.firstOrNull()?.url.orEmpty(), licence = "Public regulatory information",
                version = t.version, created = now, ttlDays = 0, regions = listOfNotNull(t.country), categories = l.categories,
            ),
        )
        l.ranges.forEach { r -> b.addRange(r.prefix, r.category, r.score ?: l.score) }
        return b.build(now = now)
    }

    /** One line per change, for the preview. */
    fun describe(t: RuleTemplate): List<String> {
        val out = ArrayList<String>()
        toRules(t).forEach { r ->
            val verb = if (r.kind == RuleKind.ALLOW) "Allow" else if (r.action == BlockAction.SILENCE) "Silence" else "Reject"
            val what = when (r.type) {
                RuleType.PREFIX -> "numbers starting ${r.pattern}"
                RuleType.EXACT -> r.pattern
                RuleType.WILDCARD -> "numbers matching ${r.pattern}"
                else -> r.title.replaceFirstChar { it.lowercase() }
            }
            out += "$verb $what" + (r.schedule?.let { s -> " (%02d:%02d–%02d:%02d)".format(s.startMinute / 60, s.startMinute % 60, s.endMinute / 60 % 24, s.endMinute % 60) } ?: "") + // locale-ok: shown to the user
                (r.note?.let { " · $it" } ?: "")
        }
        t.warnList?.let { l ->
            val verb = if (l.mode == ListMode.BLOCK) "Block" else "Warn"
            l.ranges.forEach { r -> out += "$verb: ${r.prefix}… · ${l.categories[r.category.toString()] ?: "listed"}" }
        }
        t.settings?.let { out += it.describe() }
        return out
    }

    // ---------- Signed sharing ----------

    fun sign(t: RuleTemplate, secretKey: ByteArray): String {
        validate(t)
        val body = encode(t)
        val sig = Ed25519.sign(secretKey, body.encodeToByteArray())
        val env = SignedTemplate(template = body, publicKey = Base64.getEncoder().encodeToString(Ed25519.publicKey(secretKey)), signature = Base64.getEncoder().encodeToString(sig))
        return json.encodeToString(SignedTemplate.serializer(), env)
    }

    /** Opens a signed template file and checks its signature. */
    fun open(text: String): OpenedTemplate {
        val env = try {
            json.decodeFromString(SignedTemplate.serializer(), text)
        } catch (e: Exception) {
            throw TemplateException("This isn't a Parley template file")
        }
        if (env.format != 1) throw TemplateException("This template needs a newer version of Parley")
        val key = runCatching { Base64.getDecoder().decode(env.publicKey) }.getOrNull()
        val sig = runCatching { Base64.getDecoder().decode(env.signature) }.getOrNull()
        if (key == null || key.size != 32 || sig == null || sig.size != 64) throw TemplateException("The template's signature is missing")
        if (!Ed25519.verify(key, env.template.encodeToByteArray(), sig)) throw TemplateException("The template's signature is not valid: it was changed after signing")
        return OpenedTemplate(parse(env.template), Ed25519.fingerprint(key), env.template)
    }

    /** `parley://template?d=…`: the signed file, gzipped and base64url-encoded, for a QR code. */
    fun toLink(signed: String): String {
        val zipped = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(signed.encodeToByteArray()) } }.toByteArray()
        return LINK_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(zipped)
    }

    /** Reverses [toLink]; accepts the whole link or just its `d` value. */
    fun fromLink(link: String): OpenedTemplate {
        val d = link.substringAfter("d=", link).substringBefore('&').trim()
        val bytes = try {
            Base64.getUrlDecoder().decode(d)
        } catch (e: Exception) {
            throw TemplateException("The code is damaged")
        }
        if (bytes.size > 64 * 1024) throw TemplateException("The code is too large")
        val text = try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { s ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 512 * 1024) throw TemplateException("The code is too large")
                    out.write(buf, 0, n)
                }
                out.toByteArray().decodeToString()
            }
        } catch (e: TemplateException) {
            throw e
        } catch (e: Exception) {
            throw TemplateException("The code is damaged")
        }
        return open(text)
    }

    fun fitsInQr(link: String) = link.length <= MAX_QR_LINK

    /**
     * A template made from your own rules ("share my setup"). Label rules, SIM rules and temporary rules
     * stay behind: they only make sense on this phone.
     */
    fun fromRules(id: String, name: String, author: String, rules: List<BlockRule>, version: Long): RuleTemplate = RuleTemplate(
        id = id,
        name = name,
        author = author,
        version = version,
        description = "Rules shared from Parley",
        rules = rules.filter { it.enabled && it.type != RuleType.LABEL && it.simId == null && it.expiresAt == null }.take(MAX_RULES).map { r ->
            TemplateRule(kind = r.kind, type = r.type, pattern = r.pattern, action = r.action, note = r.note, schedule = r.schedule)
        },
    )
}
