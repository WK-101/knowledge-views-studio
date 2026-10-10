package app.parley.data.people

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.util.Base64
import androidx.core.content.edit
import app.parley.common.catching
import app.parley.common.people.RelationLinks
import app.parley.data.ContactDetails
import app.parley.data.ContactDetailsJson
import java.io.File
import app.parley.common.people.MeCard
import app.parley.common.people.MeCards
import app.parley.common.people.Profile
import app.parley.common.people.ProfileService
import app.parley.data.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Your own card ("Me"), kept as a whole contact: every field and option a contact has ([ContactDetails]), edited with
 * the same form.
 *
 * - Parley's copy lives in Parley's private storage. It replaced the separate "My details", folded in once
 *   ([absorbMyDetails]) so nothing typed there is lost; "Send my details" uses the card's name and first number. A card
 *   kept before My card held every field (name, numbers, e-mails, work, websites, profiles, one address line and the
 *   note) is read into the whole contact once, and kept that way from then on.
 * - Android's profile contact (ContactsContract.Profile, "Me" in other contacts apps) is read and shown with it when
 *   the phone has one. Since Android 6 the profile is covered by the contacts permission Parley already has; the old
 *   READ_PROFILE/WRITE_PROFILE permissions no longer exist. Parley doesn't write to it: whatever is in the profile
 *   can be read by every app with contacts access, so your card stays in Parley unless you share it.
 */
class MeCardStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("me_card", Context.MODE_PRIVATE)
    private val _details = MutableStateFlow(read())

    /** Parley's own copy, as a whole contact (empty until you fill it in or it was migrated). */
    val details: StateFlow<ContactDetails> = _details.asStateFlow()

    private val _card = MutableStateFlow(MeCardDetails.toCard(_details.value))

    /** Its short form ([MeCardDetails.toCard]): the name, numbers and the parts a signed card carries. */
    val card: StateFlow<MeCard> = _card.asStateFlow()

    private val _links = MutableStateFlow(RelationLinks.decode(prefs.getString(K_LINKS, null)))

    /** Which contact each of My card's relations names (relation name key → contact), as the editor's picker linked it. */
    val links: StateFlow<Map<String, RelationLinks.Link>> = _links.asStateFlow()

    private val _shareParts = MutableStateFlow(MeCards.decodeParts(prefs.getString(K_PARTS, null)))

    /** What the QR code and the vCard include, as chosen in My card's editor (name, numbers and e-mail until then). */
    val shareParts: StateFlow<Set<MeCards.Part>> = _shareParts.asStateFlow()

    fun setShareParts(parts: Set<MeCards.Part>) {
        prefs.edit { putString(K_PARTS, MeCards.encodeParts(parts)) }
        _shareParts.value = parts
    }

    /**
     * Folds in the old "My details" (name and number) that the messaging store kept apart, once, so My card is the
     * only copy. Before the first import a filled-in card is kept as it is; after it, the old copy could only have
     * changed through "Send my details", so its values are the newer ones ([MeCards.absorbMyDetails]).
     */
    fun absorbMyDetails(name: String, number: String) {
        val d = _details.value
        val card = _card.value
        val next = when {
            !prefs.getBoolean(K_MIGRATED, false) && !card.isEmpty -> d
            card.isEmpty -> MeCardDetails.toDetails(MeCards.fromMyDetails(name, number))
            else -> MeCardDetails.withNameAndNumber(d, name.trim().ifEmpty { card.name }, number.trim().ifEmpty { card.firstNumber.orEmpty() })
        }
        // The card and the "done" flag in one write, on disk before the caller forgets the old copy: a process killed
        // in between can lose neither.
        prefs.edit(commit = true) {
            if (next != d) putString(K_DETAILS, encode(next))
            putBoolean(K_MIGRATED, true)
        }
        if (next != d) publish(next)
    }

    /** "Send my details" quick edit: the card's name and first number ([MeCardDetails.withNameAndNumber]). */
    fun setNameAndNumber(name: String, number: String) = save(MeCardDetails.withNameAndNumber(_details.value, name, number))

    /**
     * Saves the whole card. [links]: its relations' contacts, when the editor saved them too (kept as they are
     * otherwise). The photo is kept apart ([setPhoto]).
     */
    fun save(d: ContactDetails, links: Map<String, RelationLinks.Link>? = null) {
        val c = d.copy(photoUri = null)
        prefs.edit {
            putString(K_DETAILS, encode(c))
            if (links != null) putString(K_LINKS, RelationLinks.encode(links).ifEmpty { null })
        }
        if (links != null) _links.value = links
        publish(c)
    }

    /** A contact's key changed ([ContactKeys]): My card's relations to it follow. */
    fun rekeyLinks(from: String, to: String, toId: Long?) = setLinks(
        _links.value.mapValues { (_, l) -> if (l.lookupKey == from) RelationLinks.Link(to, toId ?: l.contactId) else l },
    )

    /** A contact is gone: My card's relations keep its name, without the link. */
    fun forgetLinks(key: String) = setLinks(_links.value.filterValues { it.lookupKey != key })

    private fun setLinks(links: Map<String, RelationLinks.Link>) {
        if (links == _links.value) return
        prefs.edit { putString(K_LINKS, RelationLinks.encode(links).ifEmpty { null }) }
        _links.value = links
    }

    /** Saves a card given in its short form (a restored backup from before My card held every field). */
    fun save(card: MeCard) = save(MeCardDetails.toDetails(card.cleaned()))

    private fun publish(d: ContactDetails) {
        _details.value = d.copy(photoUri = photoUri())
        _card.value = MeCardDetails.toCard(d)
    }

    // ---- The photo: a square JPEG in Parley's private files (it is your own, and goes out only when you tick it).

    private fun photoFile() = File(File(app.filesDir, "me_card"), "photo.jpg")

    /** The photo's URI for the avatar (its time in it, so a new one isn't served from a cache), or null. */
    fun photoUri(): String? = photoFile().takeIf { it.isFile }?.let { "${Uri.fromFile(it)}?v=${it.lastModified()}" }

    fun photoBytes(): ByteArray? = runCatching { photoFile().takeIf { it.isFile }?.readBytes() }.getOrNull()

    /** Stores [jpeg] as My card's photo; false when it couldn't be written. */
    suspend fun setPhoto(jpeg: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val ok = catching {
            val f = photoFile()
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "photo.tmp")
            tmp.writeBytes(jpeg)
            tmp.renameTo(f)
        }.getOrDefault(false)
        publish(_details.value)
        ok
    }

    suspend fun removePhoto() = withContext(Dispatchers.IO) {
        photoFile().delete()
        publish(_details.value)
    }

    /** For the encrypted backup: the whole card, its relations' links and its photo. */
    fun exportJson(): String? {
        val d = _details.value
        if (MeCardDetails.toCard(d).isEmpty && d == ContactDetails(photoUri = d.photoUri) && photoBytes() == null) return null
        return JSONObject().put(J_DETAILS, encode(d)).putOpt(J_LINKS, RelationLinks.encode(_links.value).ifEmpty { null })
            .putOpt(J_PHOTO, photoBytes()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }).toString()
    }

    /** Restores the card from a backup (made before or after My card held every field), unless one was already filled in here. */
    fun importJson(json: String) {
        if (!MeCardDetails.toCard(_details.value).isEmpty) return
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        if (!o.has(J_DETAILS)) {
            runCatching { decodeCard(json) }.getOrNull()?.let(::save)
            return
        }
        val d = runCatching { decode(o.getString(J_DETAILS)) }.getOrNull() ?: return
        save(d, RelationLinks.decode(o.optString(J_LINKS).ifEmpty { null }))
        o.optString(J_PHOTO).ifEmpty { null }?.let { b64 ->
            runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()?.let { bytes ->
                runCatching {
                    val f = photoFile()
                    f.parentFile?.mkdirs()
                    f.writeBytes(bytes)
                }
                publish(_details.value)
            }
        }
    }

    /**
     * The card as stored. A card kept in its short form (before My card held every field) is read into the whole
     * contact and written back that way at once, so the old copy can't drift from it.
     */
    private fun read(): ContactDetails {
        prefs.getString(K_DETAILS, null)?.let { s -> runCatching { decode(s) }.getOrNull()?.let { return it.copy(photoUri = photoUri()) } }
        val old = prefs.getString(K_CARD, null)?.let { runCatching { decodeCard(it) }.getOrNull() } ?: return ContactDetails(photoUri = photoUri())
        val d = MeCardDetails.toDetails(old.cleaned())
        prefs.edit(commit = true) {
            putString(K_DETAILS, encode(d))
            remove(K_CARD)
        }
        return d.copy(photoUri = photoUri())
    }

    /** Android's profile contact, or null when there is none (or contacts can't be read). */
    suspend fun profile(): MeCard? = withContext(Dispatchers.IO) {
        if (!Permissions.has(app, Manifest.permission.READ_CONTACTS)) return@withContext null
        val uri = Uri.withAppendedPath(ContactsContract.Profile.CONTENT_URI, ContactsContract.Contacts.Data.CONTENT_DIRECTORY)
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

    private fun encode(d: ContactDetails): String = ContactDetailsJson.encode(d.copy(photoUri = null))

    private fun decode(s: String): ContactDetails = ContactDetailsJson.decode(s).copy(photoUri = null)

    /** A card kept in its short form, before My card held every field. */
    private fun decodeCard(s: String): MeCard {
        val o = JSONObject(s)
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return MeCard(
            o.optString("name"), list("phones"), list("emails"), o.optString("company"), o.optString("title"),
            list("sites"), o.optString("address"), o.optString("note"),
            profiles = o.optJSONArray("profiles")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { p -> ProfileService.byKey(p.optString("s"))?.let { Profile(it, p.optString("h")) } }
                }
            }.orEmpty(),
        )
    }

    private companion object {
        /** The card in its short form, before My card held every field: read once into [K_DETAILS], then removed. */
        const val K_CARD = "card"
        const val K_DETAILS = "details"
        const val K_LINKS = "relation_links"
        const val J_DETAILS = "details"
        const val J_LINKS = "links"
        const val J_PHOTO = "photo"
        const val K_MIGRATED = "migrated_my_details"
        const val K_PARTS = "share_parts"
    }
}
