package app.parley.data

import org.json.JSONArray
import org.json.JSONObject

/** Compact JSON for ContactDetails (vault storage). Uses Android's built-in org.json. */
object ContactDetailsJson {
    fun encode(d: ContactDetails): String = JSONObject().apply {
        put("prefix", d.prefix); put("given", d.given); put("middle", d.middle); put("family", d.family); put("suffix", d.suffix)
        put("pg", d.phoneticGiven); put("pf", d.phoneticFamily); put("nick", d.nickname); put("company", d.company); put("title", d.title); put("note", d.note)
        put("starred", d.starred); put("ringtone", d.customRingtone ?: ""); put("photo", d.photoUri ?: "")
        put("phones", items(d.phones)); put("emails", items(d.emails)); put("sites", items(d.websites)); put("rel", items(d.relations))
        put("addr", JSONArray().apply { d.addresses.forEach { a -> put(JSONObject().put("s", a.street).put("c", a.city).put("r", a.region).put("p", a.postcode).put("k", a.country).put("t", a.type).put("l", a.label ?: "")) } })
        put("events", JSONArray().apply { d.events.forEach { e -> put(JSONObject().put("d", e.date).put("t", e.type).put("l", e.label ?: "")) } })
    }.toString()

    fun decode(s: String): ContactDetails {
        val o = JSONObject(s)
        fun str(k: String) = o.optString(k, "")
        return ContactDetails(
            prefix = str("prefix"), given = str("given"), middle = str("middle"), family = str("family"), suffix = str("suffix"),
            phoneticGiven = str("pg"), phoneticFamily = str("pf"), nickname = str("nick"), company = str("company"), title = str("title"), note = str("note"),
            starred = o.optBoolean("starred"), customRingtone = str("ringtone").ifEmpty { null }, photoUri = str("photo").ifEmpty { null },
            phones = readItems(o.optJSONArray("phones")), emails = readItems(o.optJSONArray("emails")), websites = readItems(o.optJSONArray("sites")), relations = readItems(o.optJSONArray("rel")),
            addresses = o.optJSONArray("addr")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { PostalItem(null, it.optString("s"), it.optString("c"), it.optString("r"), it.optString("p"), it.optString("k"), it.optInt("t"), it.optString("l").ifEmpty { null }) } } }.orEmpty(),
            events = o.optJSONArray("events")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { EventItem(null, it.optString("d"), it.optInt("t"), it.optString("l").ifEmpty { null }) } } }.orEmpty(),
        ).let { it.copy(displayName = it.composedName.ifBlank { it.company.ifBlank { it.phones.firstOrNull()?.value.orEmpty() } }) }
    }

    private fun items(list: List<DataItem>) = JSONArray().apply { list.filter { it.value.isNotBlank() }.forEach { put(JSONObject().put("v", it.value).put("t", it.type).put("l", it.label ?: "").put("p", it.isPrimary)) } }

    private fun readItems(a: JSONArray?): List<DataItem> =
        a?.let { (0 until it.length()).map { i -> it.getJSONObject(i).let { o -> DataItem(null, o.optString("v"), o.optInt("t"), o.optString("l").ifEmpty { null }, o.optBoolean("p")) } } }.orEmpty()
}
