package app.parley.data

import app.parley.common.people.HandleService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lossless JSON for a [ContactDetails] being edited, row ids and raw-contact bookkeeping included, so an editor
 * draft survives process death in saved state. Unlike [ContactDetailsJson] (vault storage), nothing is dropped:
 * blank rows the user just added and the ids that tell an update from an insert both come back.
 */
object ContactDraftJson {
    fun encode(d: ContactDetails): String = JSONObject().apply {
        put("id", d.id); put("lookupKey", d.lookupKey); put("displayName", d.displayName); putOpt("photoUri", d.photoUri)
        put("starred", d.starred); putOpt("ringtone", d.customRingtone); put("voicemail", d.sendToVoicemail)
        putOpt("nameId", d.nameId); put("prefix", d.prefix); put("given", d.given); put("middle", d.middle); put("family", d.family); put("suffix", d.suffix)
        put("pg", d.phoneticGiven); put("pf", d.phoneticFamily); put("pm", d.phoneticMiddle)
        putOpt("namePartsId", d.namePartsId); put("sur2", d.secondSurname); put("gen", d.generation)
        putOpt("languageId", d.languageId); put("lang", d.language)
        put("custom", JSONArray().apply {
            d.customFields.forEach { f -> put(JSONObject().putOpt("id", f.id).put("label", f.label).put("value", f.value).putOpt("mime", f.mime)) }
        })
        putOpt("nicknameId", d.nicknameId); put("nickname", d.nickname)
        putOpt("pronounsId", d.pronounsId); put("pronouns", d.pronouns)
        putOpt("orgId", d.orgId); put("company", d.company); put("title", d.title)
        put("department", d.department); put("office", d.officeLocation); put("jobDescription", d.jobDescription)
        putOpt("noteId", d.noteId); put("note", d.note)
        put("phones", items(d.phones)); put("emails", items(d.emails)); put("websites", items(d.websites)); put("relations", items(d.relations))
        put("parleyRelations", items(d.parleyRelations))
        put("addresses", JSONArray().apply {
            d.addresses.forEach { a ->
                put(
                    JSONObject().putOpt("id", a.id).put("street", a.street).put("city", a.city).put("region", a.region).put("postcode", a.postcode)
                        .put("country", a.country).put("type", a.type).putOpt("label", a.label).put("poBox", a.poBox).put("neighborhood", a.neighborhood)
                        .put("parts", a.parts),
                )
            }
        })
        put("events", JSONArray().apply {
            d.events.forEach { e ->
                put(JSONObject().putOpt("id", e.id).put("date", e.date).put("type", e.type).putOpt("label", e.label).putOpt("calendar", e.calendar))
            }
        })
        put("groupIds", longs(d.groupIds))
        put("raws", JSONArray().apply { d.rawContacts.forEach { r -> put(JSONObject().put("id", r.id).putOpt("type", r.account.type).putOpt("name", r.account.name)) } })
        putOpt("editRawId", d.editRawId)
        put("writable", longs(d.writableRawIds))
        put("readOnly", longs(d.readOnlyDataIds))
        put("handles", JSONArray().apply { d.handles.forEach { h -> put(JSONObject().putOpt("id", h.id).put("service", h.service.key).put("value", h.value).putOpt("custom", h.customProtocol)) } })
        put("context", d.context); put("pinnedNote", d.pinnedNote); put("messengerPrefs", d.messengerPrefs)
    }.toString()

    fun decode(s: String): ContactDetails {
        val o = JSONObject(s)
        return ContactDetails(
            id = o.optLong("id"), lookupKey = o.optString("lookupKey"), displayName = o.optString("displayName"), photoUri = o.str("photoUri"),
            starred = o.optBoolean("starred"), customRingtone = o.str("ringtone"), sendToVoicemail = o.optBoolean("voicemail"),
            nameId = o.long("nameId"), prefix = o.optString("prefix"), given = o.optString("given"), middle = o.optString("middle"),
            family = o.optString("family"), suffix = o.optString("suffix"), phoneticGiven = o.optString("pg"), phoneticFamily = o.optString("pf"),
            phoneticMiddle = o.optString("pm"), namePartsId = o.long("namePartsId"), secondSurname = o.optString("sur2"), generation = o.optString("gen"),
            languageId = o.long("languageId"), language = o.optString("lang"),
            customFields = o.optJSONArray("custom").objects().map { f ->
                CustomFieldItem(f.long("id"), f.optString("label"), f.optString("value"), f.str("mime"))
            },
            nicknameId = o.long("nicknameId"), nickname = o.optString("nickname"),
            pronounsId = o.long("pronounsId"), pronouns = o.optString("pronouns"),
            orgId = o.long("orgId"), company = o.optString("company"), title = o.optString("title"),
            department = o.optString("department"), officeLocation = o.optString("office"), jobDescription = o.optString("jobDescription"),
            noteId = o.long("noteId"), note = o.optString("note"),
            phones = readItems(o.optJSONArray("phones")), emails = readItems(o.optJSONArray("emails")),
            websites = readItems(o.optJSONArray("websites")), relations = readItems(o.optJSONArray("relations")),
            parleyRelations = readItems(o.optJSONArray("parleyRelations")),
            addresses = o.optJSONArray("addresses").objects().map { a ->
                PostalItem(
                    a.long("id"), a.optString("street"), a.optString("city"), a.optString("region"), a.optString("postcode"), a.optString("country"),
                    a.optInt("type"), a.str("label"), poBox = a.optString("poBox"), neighborhood = a.optString("neighborhood"), parts = a.optString("parts"),
                )
            },
            events = o.optJSONArray("events").objects().map { e ->
                EventItem(e.long("id"), e.optString("date"), e.optInt("type"), e.str("label"), e.str("calendar"))
            },
            groupIds = readLongs(o.optJSONArray("groupIds")).toSet(),
            rawContacts = o.optJSONArray("raws").objects().map { r -> RawContactRef(r.optLong("id"), AccountRef(r.str("type"), r.str("name"))) },
            editRawId = o.long("editRawId"),
            writableRawIds = readLongs(o.optJSONArray("writable")),
            readOnlyDataIds = readLongs(o.optJSONArray("readOnly")).toSet(),
            handles = o.optJSONArray("handles").objects().map { h ->
                HandleItem(h.long("id"), HandleService.byKey(h.optString("service")) ?: HandleService.OTHER, h.optString("value"), h.str("custom"))
            },
            context = o.optString("context"), pinnedNote = o.optString("pinnedNote"), messengerPrefs = o.optString("messengerPrefs"),
        )
    }

    private fun items(list: List<DataItem>) = JSONArray().apply {
        list.forEach { put(JSONObject().putOpt("id", it.id).put("value", it.value).put("type", it.type).putOpt("label", it.label).put("primary", it.isPrimary)) }
    }

    private fun readItems(a: JSONArray?): List<DataItem> =
        a.objects().map { DataItem(it.long("id"), it.optString("value"), it.optInt("type"), it.str("label"), it.optBoolean("primary")) }

    private fun longs(values: Collection<Long>) = JSONArray().apply { values.forEach { put(it) } }

    private fun readLongs(a: JSONArray?): List<Long> = if (a == null) emptyList() else (0 until a.length()).map { a.getLong(it) }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    /** Absent (or JSON null) reads as null, not as the literal "null". */
    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)

    private fun JSONObject.long(key: String): Long? = if (isNull(key)) null else optLong(key)
}
