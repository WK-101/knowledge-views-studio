package app.parley.data.people

import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import androidx.core.content.edit
import app.parley.common.people.MeCard
import app.parley.common.people.MeCards
import app.parley.data.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * I2: your own card ("Me").
 *
 * - Parley's copy lives in Parley's private storage, like the "My details" it replaces: it was read from there once
 *   ([migrateFrom]) so nothing typed before is lost, and "Send my details" keeps using its name and number.
 * - Android's profile contact (ContactsContract.Profile, "Me" in other contacts apps) is read and shown with it when
 *   the phone has one. Since Android 6 the profile is covered by the contacts permission Parley already has; the old
 *   READ_PROFILE/WRITE_PROFILE permissions no longer exist. Parley doesn't write to it: whatever is in the profile
 *   can be read by every app with contacts access, so your card stays in Parley unless you share it.
 */
class MeCardStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("me_card", Context.MODE_PRIVATE)
    private val _card = MutableStateFlow(read())

    /** Parley's own copy (empty until you fill it in or it was migrated). */
    val card: StateFlow<MeCard> = _card.asStateFlow()

    /** One-time import of the old "My details" (name and number). Returns true when something was imported. */
    fun migrateFrom(name: String, number: String): Boolean {
        if (prefs.getBoolean(K_MIGRATED, false)) return false
        val imported = MeCards.fromMyDetails(name, number)
        val merged = if (_card.value.isEmpty) imported else _card.value
        prefs.edit { putBoolean(K_MIGRATED, true) }
        if (merged != _card.value) save(merged)
        return !imported.isEmpty
    }

    fun save(card: MeCard) {
        val c = card.cleaned()
        prefs.edit { putString(K_CARD, encode(c)) }
        _card.value = c
    }

    /** For the encrypted backup. */
    fun exportJson(): String? = _card.value.takeUnless { it.isEmpty }?.let(::encode)

    /** Restores the card from a backup, unless one was already filled in here. */
    fun importJson(json: String) {
        if (!_card.value.isEmpty) return
        runCatching { decode(json) }.getOrNull()?.let(::save)
    }

    private fun read(): MeCard = prefs.getString(K_CARD, null)?.let { runCatching { decode(it) }.getOrNull() } ?: MeCard()

    /** Android's profile contact, or null when there is none (or contacts can't be read). */
    suspend fun profile(): MeCard? = withContext(Dispatchers.IO) {
        if (!Permissions.has(app, android.Manifest.permission.READ_CONTACTS)) return@withContext null
        val uri = android.net.Uri.withAppendedPath(ContactsContract.Profile.CONTENT_URI, ContactsContract.Contacts.Data.CONTENT_DIRECTORY)
        var name = ""
        val phones = ArrayList<String>()
        val emails = ArrayList<String>()
        val sites = ArrayList<String>()
        var company = ""
        var title = ""
        var address = ""
        try {
            app.contentResolver.query(uri, arrayOf(Data.MIMETYPE, Data.DATA1, Data.DATA4), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val v = c.getString(1)?.trim().orEmpty()
                    when (c.getString(0)) {
                        StructuredName.CONTENT_ITEM_TYPE -> if (name.isEmpty()) name = v
                        Phone.CONTENT_ITEM_TYPE -> if (v.isNotEmpty()) phones += v
                        Email.CONTENT_ITEM_TYPE -> if (v.isNotEmpty()) emails += v
                        Website.CONTENT_ITEM_TYPE -> if (v.isNotEmpty()) sites += v
                        Organization.CONTENT_ITEM_TYPE -> if (company.isEmpty() && title.isEmpty()) {
                            company = v
                            title = c.getString(2)?.trim().orEmpty()
                        }
                        StructuredPostal.CONTENT_ITEM_TYPE -> if (address.isEmpty()) address = v
                    }
                }
            }
        } catch (_: Exception) {
            return@withContext null
        }
        MeCard(name, phones, emails, company, title, sites, address).takeUnless { it.isEmpty }
    }

    private fun encode(c: MeCard): String = JSONObject()
        .put("name", c.name).put("phones", JSONArray(c.phones)).put("emails", JSONArray(c.emails))
        .put("company", c.company).put("title", c.title).put("sites", JSONArray(c.websites))
        .put("address", c.address).put("note", c.note).toString()

    private fun decode(s: String): MeCard {
        val o = JSONObject(s)
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return MeCard(
            o.optString("name"), list("phones"), list("emails"), o.optString("company"), o.optString("title"),
            list("sites"), o.optString("address"), o.optString("note"),
        )
    }

    private companion object {
        const val K_CARD = "card"
        const val K_MIGRATED = "migrated_my_details"
    }
}
