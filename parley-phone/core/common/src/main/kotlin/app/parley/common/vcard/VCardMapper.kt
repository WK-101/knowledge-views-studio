package app.parley.common.vcard

import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.io.scribe.ScribeIndex
import ezvcard.parameter.ImageType
import ezvcard.property.Address
import ezvcard.property.Anniversary
import ezvcard.property.Birthday
import ezvcard.property.Categories
import ezvcard.property.DateOrTimeProperty
import ezvcard.property.Email
import ezvcard.property.FormattedName
import ezvcard.property.Impp
import ezvcard.property.Kind
import ezvcard.property.Label
import ezvcard.property.Nickname
import ezvcard.property.Note
import ezvcard.property.Organization
import ezvcard.property.Photo
import ezvcard.property.ProductId
import ezvcard.property.RawProperty
import ezvcard.property.Related
import ezvcard.property.Revision
import ezvcard.property.Role
import ezvcard.property.StructuredName
import ezvcard.property.Telephone
import ezvcard.property.Title
import ezvcard.property.Uid
import ezvcard.property.Url
import ezvcard.property.VCardProperty
import ezvcard.util.PartialDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoField
import java.time.temporal.Temporal
import java.util.Base64

/**
 * Lossless mapping between [ContactRecord] and ez-vcard's [VCard].
 *
 * **Output** is vCard 4.0. Standard properties are used wherever one exists so other apps understand the
 * card; Apple/Google conventions (`itemN.X-ABLabel`, `X-ABDATE`, `X-ABRELATEDNAMES`, `X-PHONETIC-*`,
 * `X-SERVICE-TYPE`) carry custom labels and extra dates; Android's own `X-ANDROID-CUSTOM` carries data kinds
 * vCard has no property for. Anything a standard property cannot hold (a rare column, an unknown type code)
 * rides along as an `X-PARLEY-<COLUMN>` parameter, percent-encoded, so nothing is dropped.
 *
 * **Input** accepts vCard 2.1 (including QUOTED-PRINTABLE with CHARSET), 3.0 and 4.0, and the dialects of
 * Android, Apple and Google exports.
 *
 * **What "lossless" means.** A card holds one person, so [toVCard] flattens all raw contacts into one; the
 * precise promise is `fromVCard(toVCard(r)) == canonical(r)`. [canonical] documents every normalisation:
 * - raw contacts are merged into one raw with no account (the importer picks the account);
 * - duplicate rows are merged; only one name and one photo are kept;
 * - provider-computed columns are dropped (phone NORMALIZED_NUMBER, name/org *_NAME_STYLE, PHOTO_FILE_ID);
 * - group memberships travel by title (see [Col.GROUP_TITLE]); a group literally titled "starred" becomes the
 *   starred flag, as in Google's exports;
 * - a label is kept only for TYPE_CUSTOM; a label-less TYPE_CUSTOM or a missing type becomes the kind's default;
 * - per kind, one row is primary (IS_PRIMARY and IS_SUPER_PRIMARY both set); names, photos and groups carry no flags;
 * - dates use `yyyy-MM-dd`, or `--MM-dd` without a year (Apple's year 1604 means "no year").
 *
 * **Starred** is written as `X-PARLEY-STARRED:1` (not `CATEGORIES:starred`, which would collide with a real
 * label named "starred"); on import both forms are understood.
 */
object VCardMapper {
    const val X_ANDROID_CUSTOM = "X-ANDROID-CUSTOM"
    const val X_STARRED = "X-PARLEY-STARRED"
    const val X_RINGTONE = "X-PARLEY-RINGTONE"
    const val X_VOICEMAIL = "X-PARLEY-SEND-TO-VOICEMAIL"
    private const val X_BLOB = "X-PARLEY-BLOB"
    private const val X_DERIVED = "X-PARLEY-DERIVED"
    private const val X_LABEL = "X-ABLabel"
    private const val X_DATE = "X-ABDATE"
    private const val X_RELATED = "X-ABRELATEDNAMES"
    private const val X_PHONETIC_FIRST = "X-PHONETIC-FIRST-NAME"
    private const val X_PHONETIC_MIDDLE = "X-PHONETIC-MIDDLE-NAME"
    private const val X_PHONETIC_LAST = "X-PHONETIC-LAST-NAME"
    private const val X_SERVICE = "X-SERVICE-TYPE"
    private const val RESIDUAL_PREFIX = "X-PARLEY-"
    private const val GOOGLE_MY_CONTACTS = "myContacts"
    private const val STARRED_CATEGORY = "starred"

    /** Row order used by [canonical] and import: the order kinds appear in on a contact card. */
    private val MIME_ORDER = listOf(
        Mime.NAME, Mime.NICKNAME, Mime.PHONE, Mime.EMAIL, Mime.POSTAL, Mime.ORG, Mime.WEBSITE, Mime.EVENT,
        Mime.IM, Mime.SIP, Mime.RELATION, Mime.NOTE, Mime.GROUP, Mime.PHOTO,
    )
    private val MAPPED = MIME_ORDER.toSet()

    /** Columns the provider computes itself; they are neither exported nor compared. */
    private val DERIVED: Map<String, Set<String>> = mapOf(
        Mime.PHONE to setOf(Col.D4),
        Mime.NAME to setOf(Col.D10, Col.D11),
        Mime.ORG to setOf(Col.D10),
        Mime.PHOTO to setOf(Col.D14),
    )

    /** Kinds whose primary flags are meaningless once flattened to one raw contact. */
    private val NO_FLAGS = setOf(Mime.NAME, Mime.PHOTO, Mime.GROUP)

    private val DATE_FULL = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    private val DATE_BASIC = Regex("""^(\d{4})(\d{2})(\d{2})$""")
    private val DATE_TIME = Regex("""^(\d{4})-(\d{2})-(\d{2})T.*$""")
    private val DATE_NOYEAR = Regex("""^--(\d{2})-?(\d{2})$""")
    private const val APPLE_NO_YEAR = "1604"

    private val scribes = ScribeIndex()

    // ------------------------------------------------------------------------------------------------
    // Canonical form
    // ------------------------------------------------------------------------------------------------

    /**
     * The form of [record] that survives a vCard round trip (see the class documentation).
     * [groupTitles] resolves group-membership row ids that carry no [Col.GROUP_TITLE].
     */
    fun canonical(record: ContactRecord, groupTitles: Map<Long, String> = emptyMap()): ContactRecord {
        var starred = record.starred
        val rows = ArrayList<DataRow>()
        val index = HashMap<String, Int>()
        var name: DataRow? = null
        var photo: DataRow? = null
        var photoIsDefault = false
        for (raw in record.raws) for (r in raw.rows) {
            val n = normalizeRow(r, groupTitles) ?: continue
            when {
                n.mimeType == Mime.GROUP && n[Col.GROUP_TITLE].equals(STARRED_CATEGORY, ignoreCase = true) -> starred = true
                n.mimeType == Mime.NAME -> if (name == null || (name[Col.D1] != record.displayName && n[Col.D1] == record.displayName)) name = n
                n.mimeType == Mime.PHOTO -> if (photo == null || (!photoIsDefault && r.isSuperPrimary)) {
                    photo = n
                    photoIsDefault = r.isSuperPrimary
                }
                else -> {
                    val key = n.canonicalKey + "|" + (n.values - Col.ALL.toSet()).toSortedMap() + "|" + n.blob?.contentHashCode()
                    val at = index[key]
                    if (at == null) {
                        index[key] = rows.size
                        rows += n
                    } else {
                        val old = rows[at]
                        rows[at] = old.copy(isPrimary = old.isPrimary || n.isPrimary, isSuperPrimary = old.isSuperPrimary || n.isSuperPrimary)
                    }
                }
            }
        }
        val all = ArrayList<DataRow>()
        name?.let { all += it }
        all += rows
        photo?.let { all += it.copy(isPrimary = false, isSuperPrimary = false) }

        val displayName = record.displayName.ifBlank { fallbackDisplayName(all) }
        val named = all.map { r ->
            if (r.mimeType == Mime.NAME && r[Col.D1].isNullOrEmpty() && displayName.isNotEmpty()) r.copy(values = r.values + (Col.D1 to displayName)) else r
        }.filter { it.mimeType != Mime.NAME || it.values.isNotEmpty() }
        return ContactRecord(
            key = record.key,
            displayName = displayName,
            starred = starred,
            customRingtone = record.customRingtone?.takeIf { it.isNotEmpty() },
            sendToVoicemail = record.sendToVoicemail,
            raws = listOf(RawRecord(null, null, rows = sortRows(canonicalSlots(onePrimaryPerKind(named))))),
        )
    }

    private fun normalizeRow(r: DataRow, groupTitles: Map<Long, String>): DataRow? {
        val v = LinkedHashMap<String, String>()
        r.values.forEach { (k, value) -> if (!value.isNullOrEmpty()) v[k] = value }
        DERIVED[r.mimeType]?.forEach { v.remove(it) }
        var blob = r.blob?.takeIf { it.isNotEmpty() }
        when (r.mimeType) {
            Mime.GROUP -> {
                val title = v[Col.GROUP_TITLE] ?: v[Col.D1]?.toLongOrNull()?.let { groupTitles[it] }
                if (title.isNullOrBlank() || title == GOOGLE_MY_CONTACTS) return null
                v.clear()
                v[Col.GROUP_TITLE] = title.trim()
                blob = null
            }
            Mime.PHOTO -> if (blob == null) return null
            Mime.EVENT -> v[Col.D1]?.let { v[Col.D1] = normalizeDate(it) }
            Mime.SIP -> v[Col.D1]?.let { s -> stripSip(s).let { if (it.isEmpty()) v.remove(Col.D1) else v[Col.D1] = it } }
            Mime.IM -> {
                if (v[Col.D5] == null) v[Col.D5] = "-1"
                if (v[Col.D5] == "-1") {
                    val code = v[Col.D6]?.let { Types.imProtocolForService(it) }
                    if (code != null) { v[Col.D5] = code.toString(); v.remove(Col.D6) }
                } else if (v[Col.D5]?.toIntOrNull() in Types.IM_PROTOCOLS.keys) {
                    v.remove(Col.D6)
                }
            }
        }
        if (r.mimeType in MAPPED && !hasContent(r.mimeType, v)) return null
        if (r.mimeType !in MAPPED && v.isEmpty() && blob == null) return null
        if (r.mimeType == Mime.EVENT) normalizeEventType(v) else Types.BY_MIME[r.mimeType]?.let { normalizeType(it, v) }
        val flags = r.mimeType !in NO_FLAGS
        return DataRow(r.mimeType, v, blob, isPrimary = flags && (r.isPrimary || r.isSuperPrimary), isSuperPrimary = flags && (r.isPrimary || r.isSuperPrimary))
    }

    private fun normalizeType(spec: TypeSpec, v: MutableMap<String, String>) {
        val t = v[Col.D2]
        val code = t?.toIntOrNull()
        when {
            t == null -> spec.default?.let { v[Col.D2] = it }
            t == "0" -> if (v[Col.D3].isNullOrBlank()) {
                v.remove(Col.D3)
                if (spec.default == null) v.remove(Col.D2) else v[Col.D2] = spec.default
            }
            code != null && (code in spec.out || t == spec.default) -> v.remove(Col.D3)
            // Unknown codes (OEM extensions) are carried verbatim, label included.
        }
        if (spec === Types.NICKNAME && v[Col.D2] == "1") v.remove(Col.D2)
    }

    private fun normalizeEventType(v: MutableMap<String, String>) {
        when (val t = v[Col.D2]) {
            null -> v[Col.D2] = Types.EVENT_DEFAULT
            "0" -> if (v[Col.D3].isNullOrBlank()) { v.remove(Col.D3); v[Col.D2] = Types.EVENT_DEFAULT }
            "1", "2", "3" -> v.remove(Col.D3)
            else -> Unit // unknown codes (OEM extensions) are carried verbatim, label included
        }
    }

    private fun hasContent(mime: String, v: Map<String, String>): Boolean = when (mime) {
        Mime.NAME -> v.keys.any { it != Col.D10 && it != Col.D11 }
        Mime.POSTAL -> listOf(Col.D1, Col.D4, Col.D5, Col.D6, Col.D7, Col.D8, Col.D9, Col.D10).any { v[it] != null }
        Mime.ORG -> listOf(Col.D1, Col.D4, Col.D5, Col.D6, Col.D7, Col.D8, Col.D9).any { v[it] != null }
        Mime.GROUP -> v[Col.GROUP_TITLE] != null
        Mime.PHOTO -> true
        else -> v[Col.D1] != null
    }

    private fun onePrimaryPerKind(rows: List<DataRow>): List<DataRow> {
        val chosen = HashMap<String, Int>()
        rows.forEachIndexed { i, r -> if (r.isSuperPrimary && r.mimeType !in chosen) chosen[r.mimeType] = i }
        rows.forEachIndexed { i, r -> if (r.isPrimary && r.mimeType !in chosen) chosen[r.mimeType] = i }
        return rows.mapIndexed { i, r ->
            val p = chosen[r.mimeType] == i
            if (r.isPrimary == p && r.isSuperPrimary == p) r else r.copy(isPrimary = p, isSuperPrimary = p)
        }
    }

    /**
     * Orders rows by kind, then by [slot], then by original position. ez-vcard groups properties by class when
     * reading, so kinds written with several property types (BDAY / ANNIVERSARY / X-ABDATE; ORG / TITLE / ROLE)
     * cannot keep an arbitrary order; the slot gives them a fixed one instead (see [canonicalSlot]).
     */
    private fun sortRows(rows: List<Pair<DataRow, Int>>): List<DataRow> {
        val others = LinkedHashMap<String, Int>()
        rows.forEach { (r, _) -> if (r.mimeType !in MAPPED) others.putIfAbsent(r.mimeType, MIME_ORDER.size + others.size) }
        return rows.withIndex().sortedWith(
            compareBy(
                { MIME_ORDER.indexOf(it.value.first.mimeType).takeIf { i -> i >= 0 } ?: others.getValue(it.value.first.mimeType) },
                { it.value.second },
                { it.index },
            ),
        ).map { it.value.first }
    }

    private const val SLOT_FIRST = 0
    private const val SLOT_SECOND = 1
    private const val SLOT_REST = 2

    /**
     * Events: the first birthday (written as BDAY), then the first anniversary (ANNIVERSARY), then the rest
     * (X-ABDATE). Organisations: those with a company or department (ORG), then title-only (TITLE), then the rest.
     */
    private fun canonicalSlots(rows: List<DataRow>): List<Pair<DataRow, Int>> {
        var birthday = false
        var anniversary = false
        return rows.map { r ->
            r to when (r.mimeType) {
                Mime.EVENT -> when {
                    r[Col.D2] == "3" && !birthday -> { birthday = true; SLOT_FIRST }
                    r[Col.D2] == "1" && !anniversary -> { anniversary = true; SLOT_SECOND }
                    else -> SLOT_REST
                }
                Mime.ORG -> when {
                    r[Col.D1] != null || r[Col.D5] != null -> SLOT_FIRST
                    r[Col.D4] != null -> SLOT_SECOND
                    else -> SLOT_REST
                }
                else -> SLOT_FIRST
            }
        }
    }

    /** What Android would show as the name when there is no name row. */
    private fun fallbackDisplayName(rows: List<DataRow>): String {
        rows.firstOrNull { it.mimeType == Mime.NAME }?.let { n ->
            n[Col.D1]?.let { return it }
            composeName(n).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return fallbackCandidates(rows).firstOrNull().orEmpty()
    }

    private fun fallbackCandidates(rows: List<DataRow>): List<String> = buildList {
        rows.filter { it.mimeType == Mime.ORG }.forEach { it[Col.D1]?.let(::add) }
        rows.filter { it.mimeType == Mime.NICKNAME }.forEach { it[Col.D1]?.let(::add) }
        rows.filter { it.mimeType == Mime.PHONE }.forEach { it[Col.D1]?.let(::add) }
        rows.filter { it.mimeType == Mime.EMAIL }.forEach { it[Col.D1]?.let(::add) }
    }.filter { it.isNotBlank() }

    private fun composeName(n: DataRow) =
        listOf(Col.D4, Col.D2, Col.D5, Col.D3, Col.D6).mapNotNull { n[it]?.takeIf { s -> s.isNotBlank() } }.joinToString(" ")

    /** `yyyy-MM-dd`, `yyyyMMdd`, `--MMdd` and date-times become `yyyy-MM-dd` / `--MM-dd`; anything else is kept verbatim. */
    fun normalizeDate(raw: String): String {
        val s = raw.trim()
        fun full(y: String, m: String, d: String) = if (y == APPLE_NO_YEAR) "--$m-$d" else "$y-$m-$d"
        DATE_FULL.matchEntire(s)?.let { val (y, m, d) = it.destructured; return full(y, m, d) }
        DATE_BASIC.matchEntire(s)?.let { val (y, m, d) = it.destructured; return full(y, m, d) }
        DATE_TIME.matchEntire(s)?.let { val (y, m, d) = it.destructured; return full(y, m, d) }
        DATE_NOYEAR.matchEntire(s)?.let { val (m, d) = it.destructured; return "--$m-$d" }
        return raw
    }

    private fun stripSip(s: String) = if (s.startsWith("sip:", ignoreCase = true)) s.substring(4) else s

    // ------------------------------------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------------------------------------

    /** Maps [record] to a vCard 4.0 object. [groupTitles] resolves group row ids without a title. */
    fun toVCard(record: ContactRecord, groupTitles: Map<Long, String> = emptyMap()): VCard {
        val c = canonical(record, groupTitles)
        val rows = c.raws.single().rows
        val card = VCard(VCardVersion.V4_0)
        var items = 0
        fun newGroup() = "item${++items}"
        fun add(p: VCardProperty): VCardProperty = p.also { card.addProperty(it) }
        fun raw(name: String, value: String, group: String? = null) = add(RawProperty(name, escapeRaw(value)).also { it.group = group })
        /** Writes TYPE tokens or an X-ABLabel for [spec]; returns the columns represented. */
        fun typed(p: VCardProperty, spec: TypeSpec, v: Map<String, String>, extra: List<VCardProperty> = emptyList()): Set<String> {
            val t = v[Col.D2] ?: return emptySet()
            val code = t.toIntOrNull()
            val tokens = code?.let { spec.tokensFor(it) }
            return when {
                tokens != null -> { tokens.forEach { p.parameters.addType(it) }; setOf(Col.D2, Col.D3) }
                t == "0" && v[Col.D3] != null -> {
                    val g = p.group ?: newGroup()
                    p.group = g
                    extra.forEach { it.group = g }
                    raw(X_LABEL, v.getValue(Col.D3), g)
                    setOf(Col.D2, Col.D3)
                }
                t == spec.default -> setOf(Col.D2, Col.D3)
                else -> emptySet()
            }
        }
        fun finish(p: VCardProperty, r: DataRow, consumed: Set<String>) {
            if (r.isPrimary) p.parameters.pref = 1
            r.values.forEach { (k, value) -> if (k !in consumed && value != null) p.addParameter(residualParam(k), encodeParam(value)) }
        }

        // Name
        val nameRow = rows.firstOrNull { it.mimeType == Mime.NAME }
        val fn = add(FormattedName(c.displayName.ifEmpty { nameRow?.let(::composeName).orEmpty() }))
        // Without a name row the display name is derived (company, number...); say so, so no name row is invented.
        if (nameRow == null) fn.addParameter(X_DERIVED, "1")
        if (nameRow != null) {
            val v = nameRow.present()
            val n = StructuredName()
            n.family = v[Col.D3]
            n.given = v[Col.D2]
            v[Col.D5]?.let { n.additionalNames += it }
            v[Col.D4]?.let { n.prefixes += it }
            v[Col.D6]?.let { n.suffixes += it }
            // SORT-AS (family, given) lets vCard 4.0 readers sort by pronunciation, e.g. for Japanese names.
            if (v[Col.D9] != null || v[Col.D7] != null) {
                n.parameters.setSortAs(*listOf(v[Col.D9].orEmpty(), v[Col.D7].orEmpty()).dropLastWhile { it.isEmpty() }.toTypedArray())
            }
            add(n)
            val consumed = mutableSetOf(Col.D2, Col.D3, Col.D4, Col.D5, Col.D6, Col.D7, Col.D8, Col.D9)
            if (v[Col.D1] == c.displayName) consumed += Col.D1
            finish(n, nameRow, consumed)
            v[Col.D7]?.let { raw(X_PHONETIC_FIRST, it) }
            v[Col.D8]?.let { raw(X_PHONETIC_MIDDLE, it) }
            v[Col.D9]?.let { raw(X_PHONETIC_LAST, it) }
        }
        if (c.key.isNotEmpty()) add(Uid(c.key))

        var orgIndex = 0
        var hadBirthday = false
        var hadAnniversary = false
        val categories = ArrayList<String>()
        for (r in rows) {
            val v = r.present()
            when (r.mimeType) {
                Mime.NAME -> Unit
                Mime.NICKNAME -> {
                    val p = Nickname().also { it.values += v.getValue(Col.D1) }
                    add(p)
                    finish(p, r, typed(p, Types.NICKNAME, v) + Col.D1)
                }
                Mime.PHONE -> {
                    val p = add(Telephone(v.getValue(Col.D1)))
                    finish(p, r, typed(p, Types.PHONE, v) + Col.D1)
                }
                Mime.EMAIL -> {
                    val p = add(Email(v.getValue(Col.D1)))
                    finish(p, r, typed(p, Types.EMAIL, v) + Col.D1)
                }
                Mime.POSTAL -> {
                    val a = Address()
                    a.poBox = v[Col.D5]
                    a.extendedAddress = v[Col.D6]
                    a.streetAddress = v[Col.D4]
                    a.locality = v[Col.D7]
                    a.region = v[Col.D8]
                    a.postalCode = v[Col.D9]
                    a.country = v[Col.D10]
                    a.label = v[Col.D1]
                    add(a)
                    finish(a, r, typed(a, Types.POSTAL, v) + setOf(Col.D1, Col.D4, Col.D5, Col.D6, Col.D7, Col.D8, Col.D9, Col.D10))
                }
                Mime.ORG -> {
                    val parts = ArrayList<VCardProperty>()
                    if (v[Col.D1] != null || v[Col.D5] != null) {
                        parts += Organization().also { o -> o.values += v[Col.D1].orEmpty(); v[Col.D5]?.let { o.values += it } }
                    }
                    v[Col.D4]?.let { parts += Title(it) }
                    v[Col.D6]?.let { parts += Role(it) }
                    if (parts.isEmpty()) parts += Organization().also { it.values += "" }
                    val anchor = parts.first()
                    if (orgIndex++ > 0) newGroup().let { g -> parts.forEach { it.group = g } }
                    parts.forEach { add(it) }
                    finish(anchor, r, typed(anchor, Types.ORG, v, parts.drop(1)) + setOf(Col.D1, Col.D4, Col.D5, Col.D6))
                }
                Mime.WEBSITE -> {
                    val p = add(Url(v.getValue(Col.D1)))
                    finish(p, r, typed(p, Types.WEBSITE, v) + Col.D1)
                }
                Mime.EVENT -> {
                    val date = v.getValue(Col.D1)
                    val t = v[Col.D2]
                    val p: VCardProperty
                    val consumed = mutableSetOf(Col.D1)
                    when {
                        t == "3" && !hadBirthday -> { hadBirthday = true; p = add(dateProperty(date, ::Birthday, ::Birthday, ::Birthday)); consumed += Col.D2 }
                        t == "1" && !hadAnniversary -> { hadAnniversary = true; p = add(dateProperty(date, ::Anniversary, ::Anniversary, ::Anniversary)); consumed += Col.D2 }
                        else -> {
                            val g = newGroup()
                            p = raw(X_DATE, date, g)
                            val label = when (t) {
                                "1" -> TypeSpec.appleLabel("Anniversary")
                                "2" -> TypeSpec.appleLabel("Other")
                                "3" -> TypeSpec.appleLabel("Birthday")
                                "0" -> v[Col.D3]
                                else -> null
                            }
                            if (label != null) { raw(X_LABEL, label, g); consumed += Col.D2; if (t == "0") consumed += Col.D3 }
                        }
                    }
                    finish(p, r, consumed)
                }
                Mime.IM -> {
                    val handle = v.getValue(Col.D1)
                    val code = v[Col.D5]?.toIntOrNull()
                    val known = code?.let { Types.IM_PROTOCOLS[it] }
                    val consumed = mutableSetOf(Col.D1)
                    val p = when {
                        known != null -> Impp(known.first, handle).also { it.addParameter(X_SERVICE, known.second); consumed += Col.D5 }
                        code == -1 -> Impp("x-apple", handle).also { i -> consumed += Col.D5; v[Col.D6]?.let { i.addParameter(X_SERVICE, it); consumed += Col.D6 } }
                        else -> Impp("x-apple", handle)
                    }
                    add(p)
                    finish(p, r, typed(p, Types.IM, v) + consumed)
                }
                Mime.SIP -> {
                    val p = add(Impp("sip", v.getValue(Col.D1)))
                    finish(p, r, typed(p, Types.SIP, v) + Col.D1)
                }
                Mime.RELATION -> {
                    val p = add(Related().also { it.text = v.getValue(Col.D1) })
                    finish(p, r, typed(p, Types.RELATION, v) + Col.D1)
                }
                Mime.NOTE -> {
                    val p = add(Note(v.getValue(Col.D1)))
                    finish(p, r, setOf(Col.D1))
                }
                Mime.GROUP -> categories += v.getValue(Col.GROUP_TITLE)
                Mime.PHOTO -> {
                    val bytes = r.blob ?: continue
                    val p = add(Photo(bytes, imageType(bytes)))
                    finish(p, r, emptySet())
                }
                else -> {
                    val slots = (1..15).map { i ->
                        if (i == 15 && r.blob != null) Base64.getEncoder().encodeToString(r.blob) else v["data$i"].orEmpty()
                    }
                    val p = add(RawProperty(X_ANDROID_CUSTOM, (listOf(r.mimeType) + slots).joinToString(";") { escapeCustom(it) }))
                    if (r.blob != null) p.addParameter(X_BLOB, "base64")
                    finish(p, r, Col.ALL.toSet())
                }
            }
        }
        if (categories.isNotEmpty()) add(Categories().also { it.values.addAll(categories) })
        if (c.starred) raw(X_STARRED, "1")
        if (c.sendToVoicemail) raw(X_VOICEMAIL, "1")
        c.customRingtone?.let { raw(X_RINGTONE, it) }
        return card
    }

    private fun <P : DateOrTimeProperty> dateProperty(
        date: String,
        ofDate: (Temporal) -> P,
        ofPartial: (PartialDate) -> P,
        ofText: (String) -> P,
    ): P {
        DATE_FULL.matchEntire(date)?.let { val (y, m, d) = it.destructured; runCatching { return ofDate(LocalDate.of(y.toInt(), m.toInt(), d.toInt())) } }
        DATE_NOYEAR.matchEntire(date)?.let { val (m, d) = it.destructured; return ofPartial(PartialDate.builder().month(m.toInt()).date(d.toInt()).build()) }
        return ofText(date)
    }

    private fun imageType(bytes: ByteArray): ImageType = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> ImageType.PNG
        bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() -> ImageType.GIF
        bytes.size > 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> ImageType.get(null, "image/webp", "webp")
        else -> ImageType.JPEG
    }

    // ------------------------------------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------------------------------------

    /**
     * Maps one parsed card to a record with a single account-less raw contact. Properties with no place in an
     * Android contact are counted in [unmapped] (by property name) instead of being dropped silently.
     */
    fun fromVCard(card: VCard, unmapped: MutableMap<String, Int>? = null): ContactRecord {
        fun skip(name: String) { unmapped?.let { it[name] = (it[name] ?: 0) + 1 } }
        val props = card.properties.toList()
        val labels = HashMap<String, String>()
        props.forEach { p -> if (p is RawProperty && p.propertyName.equals(X_LABEL, true) && p.group != null) labels.putIfAbsent(p.group.lowercase(), unescapeRaw(p.value.orEmpty())) }
        fun labelOf(p: VCardProperty) = p.group?.let { labels[it.lowercase()] }

        val rows = ArrayList<Pair<DataRow, Int>>() // row with preference rank (lower = more preferred)
        val slots = java.util.IdentityHashMap<DataRow, Int>()
        var birthdaySlot = false
        var anniversarySlot = false
        var fnDerived = false
        var starred = false
        var voicemail = false
        var ringtone: String? = null
        var uid: String? = null
        var fn: String? = null
        var nameRow: MutableMap<String, String>? = null
        var namePrefs: VCardProperty? = null
        val phonetic = HashMap<String, String>()
        var sortAs: List<String> = emptyList()
        var photoDone = false

        // Organization units: ORG, TITLE and ROLE belong together when they share a group; ungrouped ones pair in order.
        class OrgUnit(val pos: Int, val slot: Int) { var org: Organization? = null; var title: Title? = null; var role: Role? = null }
        val orgUnits = ArrayList<OrgUnit>()
        val orgByGroup = HashMap<String, OrgUnit>()
        val ungrouped = HashMap<String, Int>()
        fun orgUnit(p: VCardProperty, kind: String): OrgUnit {
            val slot = when (p) { is Organization -> SLOT_FIRST; is Title -> SLOT_SECOND; else -> SLOT_REST }
            fun create() = OrgUnit(rows.size, slot).also { orgUnits += it; rows += PLACEHOLDER to 0 }
            p.group?.lowercase()?.let { g -> return orgByGroup.getOrPut(g) { create() } }
            val i = ungrouped[kind] ?: 0
            ungrouped[kind] = i + 1
            val list = orgUnits.filter { u -> u !in orgByGroup.values }
            return list.getOrNull(i) ?: create()
        }

        fun pref(p: VCardProperty): Int? =
            p.parameters.pref ?: if (p.parameters.types.any { it.equals("pref", true) }) 1 else null

        fun emit(mime: String, values: MutableMap<String, String>, p: VCardProperty?, spec: TypeSpec? = null, blob: ByteArray? = null): DataRow {
            if (spec != null) {
                val (t, l) = spec.resolve(p?.parameters?.types.orEmpty(), p?.let(::labelOf))
                t?.let { values[Col.D2] = it }
                l?.let { values[Col.D3] = it }
            }
            p?.let { applyResidual(it, values) }
            values.entries.removeAll { it.value.isEmpty() }
            val rank = p?.let(::pref)
            val row = DataRow(mime, values.toMap(), blob, isPrimary = rank != null, isSuperPrimary = rank != null)
            rows += row to (rank ?: Int.MAX_VALUE)
            return row
        }
        fun birthday(date: String, p: VCardProperty) {
            val r = emit(Mime.EVENT, mutableMapOf(Col.D1 to date, Col.D2 to "3"), p)
            if (!birthdaySlot) { birthdaySlot = true; slots[r] = SLOT_FIRST }
        }
        fun anniversary(date: String, p: VCardProperty) {
            val r = emit(Mime.EVENT, mutableMapOf(Col.D1 to date, Col.D2 to "1"), p)
            if (!anniversarySlot) { anniversarySlot = true; slots[r] = SLOT_SECOND }
        }

        for (p in props) {
            when (p) {
                is FormattedName -> if (fn == null) {
                    fn = p.value
                    fnDerived = p.getParameter(X_DERIVED) != null
                } else skip("FN")
                is StructuredName -> if (nameRow == null) {
                    nameRow = linkedMapOf<String, String>().apply {
                        p.given?.let { put(Col.D2, it) }
                        p.family?.let { put(Col.D3, it) }
                        p.prefixes.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { put(Col.D4, it.joinToString(" ")) }
                        p.additionalNames.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { put(Col.D5, it.joinToString(" ")) }
                        p.suffixes.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { put(Col.D6, it.joinToString(" ")) }
                    }
                    namePrefs = p
                    sortAs = p.parameters.sortAs
                } else skip("N")
                is Nickname -> p.values.filter { it.isNotBlank() }.forEach { emit(Mime.NICKNAME, mutableMapOf(Col.D1 to it), p, Types.NICKNAME) }
                is Telephone -> {
                    val number = p.text ?: p.uri?.let { u -> u.number + (u.extension?.let { ",$it" } ?: "") }
                    if (number.isNullOrBlank()) skip("TEL") else emit(Mime.PHONE, mutableMapOf(Col.D1 to number), p, Types.PHONE)
                }
                is Email -> if (p.value.isNullOrBlank()) skip("EMAIL") else emit(Mime.EMAIL, mutableMapOf(Col.D1 to p.value), p, Types.EMAIL)
                is Address -> {
                    val v = linkedMapOf<String, String>()
                    fun put(col: String, list: List<String>) { list.filter { it.isNotEmpty() }.joinToString(", ").takeIf { it.isNotEmpty() }?.let { v[col] = it } }
                    put(Col.D5, p.poBoxes); put(Col.D6, p.extendedAddresses); put(Col.D4, p.streetAddresses)
                    put(Col.D7, p.localities); put(Col.D8, p.regions); put(Col.D9, p.postalCodes); put(Col.D10, p.countries)
                    p.label?.takeIf { it.isNotEmpty() }?.let { v[Col.D1] = it }
                    if (v.isEmpty()) skip("ADR") else emit(Mime.POSTAL, v, p, Types.POSTAL)
                }
                is Organization -> orgUnit(p, "ORG").org = p
                is Title -> orgUnit(p, "TITLE").title = p
                is Role -> orgUnit(p, "ROLE").role = p
                is Url -> if (p.value.isNullOrBlank()) skip("URL") else emit(Mime.WEBSITE, mutableMapOf(Col.D1 to p.value.replace("\\:", ":")), p, Types.WEBSITE)
                is Note -> if (!p.value.isNullOrEmpty()) emit(Mime.NOTE, mutableMapOf(Col.D1 to p.value), p)
                is Birthday -> dateText(p)?.let { birthday(it, p) } ?: skip("BDAY")
                is Anniversary -> dateText(p)?.let { anniversary(it, p) } ?: skip("ANNIVERSARY")
                is Impp -> importImpp(p, ::emit) ?: skip("IMPP")
                is Related -> {
                    val name = p.text ?: p.uri
                    if (name.isNullOrBlank()) skip("RELATED") else emit(Mime.RELATION, mutableMapOf(Col.D1 to name), p, Types.RELATION)
                }
                is Photo -> {
                    val data = p.data
                    if (data != null && data.isNotEmpty() && !photoDone) {
                        photoDone = true
                        emit(Mime.PHOTO, linkedMapOf(), p, blob = data)
                    } else skip(if (data == null) "PHOTO (link)" else "PHOTO (extra)")
                }
                is Categories -> p.values.map { it.trim() }.filter { it.isNotEmpty() }.forEach { title ->
                    when {
                        title.equals(STARRED_CATEGORY, ignoreCase = true) -> starred = true
                        title == GOOGLE_MY_CONTACTS -> Unit
                        else -> rows += DataRow(Mime.GROUP, mapOf(Col.GROUP_TITLE to title)) to Int.MAX_VALUE
                    }
                }
                is Label -> if (!p.value.isNullOrBlank()) emit(Mime.POSTAL, mutableMapOf(Col.D1 to p.value), p, Types.POSTAL)
                is Uid -> uid = p.value
                is ProductId, is Revision -> Unit
                is Kind -> if (p.isGroup) skip("KIND")
                is RawProperty -> {
                    val name = p.propertyName.uppercase()
                    val value = p.value.orEmpty()
                    when {
                        name == X_LABEL.uppercase() -> Unit
                        name == X_ANDROID_CUSTOM -> importAndroidCustom(p, ::emit) ?: skip(X_ANDROID_CUSTOM)
                        name == X_PHONETIC_FIRST -> phonetic[Col.D7] = unescapeRaw(value)
                        name == X_PHONETIC_MIDDLE -> phonetic[Col.D8] = unescapeRaw(value)
                        name == X_PHONETIC_LAST -> phonetic[Col.D9] = unescapeRaw(value)
                        name == X_DATE.uppercase() || name == "X-EVENT" -> {
                            val date = normalizeDate(unescapeRaw(value))
                            if (date.isBlank()) skip(name) else {
                                val label = labelOf(p)
                                val (t, l) = when {
                                    label != null -> TypeSpec.appleInner(label)?.let { sys -> Types.EVENT_APPLE[sys.lowercase()]?.let { it.toString() to null } ?: ("0" to sys) } ?: ("0" to label)
                                    else -> Types.EVENT_DEFAULT to null
                                }
                                val v = linkedMapOf(Col.D1 to date, Col.D2 to t)
                                l?.let { v[Col.D3] = it }
                                emit(Mime.EVENT, v, p)
                            }
                        }
                        // ez-vcard keeps dates it cannot parse (e.g. 3.0 "--04-13") as raw BDAY / ANNIVERSARY.
                        name == "BDAY" -> normalizeDate(unescapeRaw(value)).takeIf { it.isNotBlank() }?.let { birthday(it, p) } ?: skip(name)
                        name == "ANNIVERSARY" || name == "X-ANNIVERSARY" ->
                            normalizeDate(unescapeRaw(value)).takeIf { it.isNotBlank() }?.let { anniversary(it, p) } ?: skip(name)
                        name == X_RELATED.uppercase() -> unescapeRaw(value).takeIf { it.isNotBlank() }
                            ?.let { emit(Mime.RELATION, mutableMapOf(Col.D1 to it), p, Types.RELATION) } ?: skip(name)
                        name == "X-SIP" -> stripSip(unescapeRaw(value)).takeIf { it.isNotBlank() }
                            ?.let { emit(Mime.SIP, mutableMapOf(Col.D1 to it), p, Types.SIP) } ?: skip(name)
                        name in Types.LEGACY_IM -> unescapeRaw(value).takeIf { it.isNotBlank() }
                            ?.let { emit(Mime.IM, mutableMapOf(Col.D1 to it, Col.D5 to Types.LEGACY_IM.getValue(name).toString()), p, Types.IM) } ?: skip(name)
                        name == X_STARRED -> starred = value.trim() == "1" || value.trim().equals("true", true)
                        name == X_VOICEMAIL -> voicemail = value.trim() == "1" || value.trim().equals("true", true)
                        name == X_RINGTONE -> ringtone = unescapeRaw(value).takeIf { it.isNotEmpty() }
                        name in SILENT_RAW -> Unit
                        else -> skip(p.propertyName)
                    }
                }
                else -> skip(scribes.getPropertyScribe(p)?.propertyName ?: p.javaClass.simpleName)
            }
        }

        // Organization units become rows at the position of their first property.
        for (u in orgUnits) {
            val v = linkedMapOf<String, String>()
            u.org?.values?.let { vals ->
                vals.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { v[Col.D1] = it }
                vals.drop(1).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { v[Col.D5] = it.joinToString(", ") }
            }
            u.title?.value?.takeIf { it.isNotEmpty() }?.let { v[Col.D4] = it }
            u.role?.value?.takeIf { it.isNotEmpty() }?.let { v[Col.D6] = it }
            val anchor: VCardProperty? = u.org ?: u.title ?: u.role
            val (t, l) = Types.ORG.resolve(anchor?.parameters?.types.orEmpty(), anchor?.let(::labelOf))
            t?.let { v[Col.D2] = it }
            l?.let { v[Col.D3] = it }
            anchor?.let { applyResidual(it, v) }
            v.entries.removeAll { it.value.isEmpty() }
            val rank = anchor?.let(::pref)
            val row = DataRow(Mime.ORG, v.toMap(), isPrimary = rank != null, isSuperPrimary = rank != null)
            rows[u.pos] = (if (hasContent(Mime.ORG, v)) row else PLACEHOLDER) to (rank ?: Int.MAX_VALUE)
            slots[row] = u.slot
        }

        // Name row: N (or FN alone when it isn't just the company/number shown in its place) plus phonetics.
        val nonName = rows.map { it.first }.filter { it !== PLACEHOLDER }
        if (phonetic.isEmpty() && nameRow != null && sortAs.isNotEmpty()) {
            val fam = sortAs.getOrNull(0).orEmpty()
            val giv = sortAs.getOrNull(1).orEmpty()
            if (fam.isNotEmpty() && fam != nameRow!![Col.D3]) phonetic[Col.D9] = fam
            if (giv.isNotEmpty() && giv != nameRow!![Col.D2]) phonetic[Col.D7] = giv
        }
        if (nameRow == null && (phonetic.isNotEmpty() || (!fnDerived && !fn.isNullOrBlank() && fn !in fallbackCandidates(nonName)))) nameRow = linkedMapOf()
        var nameData: DataRow? = null
        nameRow?.let { v ->
            fn?.takeIf { it.isNotEmpty() }?.let { v[Col.D1] = it }
            v.putAll(phonetic)
            namePrefs?.let { applyResidual(it, v) }
            v.entries.removeAll { it.value.isEmpty() }
            if (v.isNotEmpty()) nameData = DataRow(Mime.NAME, v.toMap())
        }

        val all = ArrayList<Pair<DataRow, Int>>()
        nameData?.let { all += it to SLOT_FIRST }
        // Primary: per kind, the row with the lowest PREF wins.
        val best = HashMap<String, Pair<Int, Int>>()
        rows.forEachIndexed { i, (r, rank) ->
            if (r === PLACEHOLDER || rank == Int.MAX_VALUE) return@forEachIndexed
            val cur = best[r.mimeType]
            if (cur == null || rank < cur.second) best[r.mimeType] = i to rank
        }
        rows.forEachIndexed { i, (r, _) ->
            if (r === PLACEHOLDER) return@forEachIndexed
            val p = best[r.mimeType]?.first == i && r.mimeType !in NO_FLAGS
            val slot = slots[r] ?: if (r.mimeType == Mime.EVENT) SLOT_REST else SLOT_FIRST
            all += (if (r.isPrimary == p && r.isSuperPrimary == p) r else r.copy(isPrimary = p, isSuperPrimary = p)) to slot
        }
        val displayName = fn?.takeIf { it.isNotBlank() } ?: fallbackDisplayName(all.map { it.first })
        return ContactRecord(
            key = uid.orEmpty(),
            displayName = displayName,
            starred = starred,
            customRingtone = ringtone,
            sendToVoicemail = voicemail,
            raws = listOf(RawRecord(null, null, rows = sortRows(all))),
        )
    }

    private val PLACEHOLDER = DataRow("", emptyMap())

    /** The row's non-null values (canonical rows never hold nulls, but the model allows them). */
    private fun DataRow.present(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        values.forEach { (k, v) -> if (v != null) out[k] = v }
        return out
    }

    /** Raw properties that carry nothing for an Android contact and are ignored without a report entry. */
    private val SILENT_RAW = setOf(
        "X-ABSHOWAS", "X-ABADR", "X-ABUID", "X-ABORG", "X-ABPERSON", "X-IMAGETYPE", "X-IMAGEHASH", "X-SHARED-PHOTO-DISPLAY-PREF",
        "X-ABCROPRECTANGLE", "X-PARLEY-BLOB", "X-ANDROID-DATA-SET", "X-PHONETIC-ORG", "X-MS-OL-DEFAULT-POSTAL-ADDRESS",
    )

    private fun dateText(p: DateOrTimeProperty): String? {
        p.date?.let { return formatTemporal(it) }
        p.partialDate?.let { pd ->
            val m = pd.month
            val d = pd.date
            if (m != null && d != null) return normalizeDate(if (pd.year != null) "%04d-%02d-%02d".format(pd.year, m, d) else "--%02d-%02d".format(m, d))
            return pd.toISO8601(true)
        }
        return p.text?.takeIf { it.isNotBlank() }?.let(::normalizeDate)
    }

    private fun formatTemporal(t: Temporal): String? {
        val local = when (t) {
            is Instant -> t.atOffset(ZoneOffset.UTC)
            else -> t
        }
        if (!local.isSupported(ChronoField.YEAR) || !local.isSupported(ChronoField.MONTH_OF_YEAR) || !local.isSupported(ChronoField.DAY_OF_MONTH)) return null
        return normalizeDate("%04d-%02d-%02d".format(local.get(ChronoField.YEAR), local.get(ChronoField.MONTH_OF_YEAR), local.get(ChronoField.DAY_OF_MONTH)))
    }

    private fun importImpp(p: Impp, emit: (String, MutableMap<String, String>, VCardProperty?, TypeSpec?, ByteArray?) -> Unit): Unit? {
        val uri = p.uri ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val handle = (p.handle ?: uri.schemeSpecificPart)?.takeIf { it.isNotBlank() } ?: return null
        val service = p.getParameter(X_SERVICE)?.takeIf { it.isNotBlank() }
        if (scheme == "sip" && (service == null || service.equals("sip", true))) {
            emit(Mime.SIP, mutableMapOf(Col.D1 to handle), p, Types.SIP, null)
            return Unit
        }
        val code = service?.let { Types.imProtocolForService(it) } ?: if (service == null) Types.imProtocolForScheme(scheme) else null
        val v = linkedMapOf(Col.D1 to handle)
        if (code != null) v[Col.D5] = code.toString() else {
            v[Col.D5] = "-1"
            (service ?: scheme.takeIf { it != "x-apple" })?.let { v[Col.D6] = it }
        }
        emit(Mime.IM, v, p, Types.IM, null)
        return Unit
    }

    private fun importAndroidCustom(p: RawProperty, emit: (String, MutableMap<String, String>, VCardProperty?, TypeSpec?, ByteArray?) -> Unit): Unit? {
        val parts = splitCustom(p.value.orEmpty())
        val mime = parts.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val v = linkedMapOf<String, String>()
        parts.drop(1).take(14).forEachIndexed { i, s -> if (s.isNotEmpty()) v["data${i + 1}"] = s }
        val slot15 = parts.getOrNull(15)?.takeIf { it.isNotEmpty() }
        val blob = slot15?.let {
            if (p.getParameter(X_BLOB) != null) runCatching { Base64.getDecoder().decode(it) }.getOrNull() else it.toByteArray(Charsets.UTF_8)
        }
        if (mime == Mime.GROUP) {
            // Android's composer writes group membership rows with a device-local id only: meaningless elsewhere.
            return Unit
        }
        emit(mime, v, p, null, blob)
        return Unit
    }

    // ------------------------------------------------------------------------------------------------
    // Escaping
    // ------------------------------------------------------------------------------------------------

    private fun residualParam(key: String) = RESIDUAL_PREFIX + key.uppercase().replace('_', '-')

    /** Overlays X-PARLEY-<COLUMN> parameters onto [values]; they are authoritative for their column. */
    private fun applyResidual(p: VCardProperty, values: MutableMap<String, String>) {
        for (name in p.parameters.keySet()) {
            val up = name.uppercase()
            if (!up.startsWith(RESIDUAL_PREFIX) || up == X_BLOB || up == X_DERIVED) continue
            val key = up.removePrefix(RESIDUAL_PREFIX).lowercase().replace('-', '_')
            p.parameters.get(name).firstOrNull()?.let { values[key] = decodeParam(it) }
        }
    }

    private fun encodeParam(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) || c == '.'.code || c == '_'.code || c == '~'.code || c == '-'.code) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }
        return sb.toString()
    }

    private fun decodeParam(s: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length + 0 && i + 2 <= s.length - 1) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) { out.write(hex); i += 3; continue }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Escapes a value written verbatim by ez-vcard (raw properties): backslashes and line breaks. */
    private fun escapeRaw(s: String) = s.replace("\\", "\\\\").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

    /** Undoes vCard text escaping (`\\`, `\n`, `\N`, `\,`, `\;`, `\:`) in a raw property value. */
    internal fun unescapeRaw(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    '\\', ',', ';', ':' -> sb.append(n)
                    else -> sb.append(c).append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** One X-ANDROID-CUSTOM field: escapes the separator too, like Android's own composer. */
    private fun escapeCustom(s: String) = escapeRaw(s).replace(";", "\\;").replace(",", "\\,")

    /** Splits an X-ANDROID-CUSTOM value on unescaped semicolons and unescapes each field. */
    internal fun splitCustom(value: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                cur.append(c).append(value[i + 1])
                i += 2
                continue
            }
            if (c == ';') {
                out += unescapeRaw(cur.toString())
                cur.setLength(0)
            } else cur.append(c)
            i++
        }
        out += unescapeRaw(cur.toString())
        return out
    }
}
