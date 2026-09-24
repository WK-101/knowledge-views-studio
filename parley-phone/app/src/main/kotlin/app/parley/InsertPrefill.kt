package app.parley

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Intents.Insert
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.EventItem
import app.parley.data.PostalItem

/**
 * Turns ContactsContract.Intents.Insert extras (sent by dialers, browsers, e-mail apps…) into a
 * draft. Reads CharSequence extras because many apps send Spannables, and Insert.DATA rows.
 */
object InsertPrefill {
    fun from(intent: Intent): ContactDetails {
        fun cs(key: String) = intent.getCharSequenceExtra(key)?.toString()?.trim().orEmpty()
        val name = cs(Insert.NAME)
        val parts = name.split(Regex("\\s+"), limit = 2)
        var d = ContactDetails(
            given = parts.getOrElse(0) { "" },
            family = parts.getOrElse(1) { "" },
            phoneticGiven = cs(Insert.PHONETIC_NAME),
            company = cs(Insert.COMPANY),
            title = cs(Insert.JOB_TITLE),
            note = cs(Insert.NOTES),
        )
        val phones = ArrayList<DataItem>()
        listOf(
            Triple(Insert.PHONE, Insert.PHONE_TYPE, Insert.PHONE_ISPRIMARY),
            Triple(Insert.SECONDARY_PHONE, Insert.SECONDARY_PHONE_TYPE, null),
            Triple(Insert.TERTIARY_PHONE, Insert.TERTIARY_PHONE_TYPE, null),
        ).forEach { (k, t, prim) ->
            val v = cs(k)
            if (v.isNotEmpty()) phones += DataItem(value = v, type = typeOf(intent, t, Phone.TYPE_MOBILE), isPrimary = prim?.let { intent.getBooleanExtra(it, false) } ?: false)
        }
        val emails = ArrayList<DataItem>()
        listOf(Insert.EMAIL to Insert.EMAIL_TYPE, Insert.SECONDARY_EMAIL to Insert.SECONDARY_EMAIL_TYPE, Insert.TERTIARY_EMAIL to Insert.TERTIARY_EMAIL_TYPE).forEach { (k, t) ->
            val v = cs(k)
            if (v.isNotEmpty()) emails += DataItem(value = v, type = typeOf(intent, t, Email.TYPE_HOME))
        }
        val addresses = ArrayList<PostalItem>()
        cs(Insert.POSTAL).takeIf { it.isNotEmpty() }?.let { addresses += PostalItem(street = it, type = typeOf(intent, Insert.POSTAL_TYPE, StructuredPostal.TYPE_HOME)) }
        val sites = ArrayList<DataItem>()
        val events = ArrayList<EventItem>()

        @Suppress("DEPRECATION")
        val rows: List<ContentValues> = (
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Insert.DATA, ContentValues::class.java)
            else intent.getParcelableArrayListExtra(Insert.DATA)
            ).orEmpty()
        for (cv in rows) {
            when (cv.getAsString(Data.MIMETYPE)) {
                Phone.CONTENT_ITEM_TYPE -> cv.getAsString(Phone.NUMBER)?.let { phones += DataItem(value = it, type = cv.getAsInteger(Phone.TYPE) ?: Phone.TYPE_MOBILE, label = cv.getAsString(Phone.LABEL)) }
                Email.CONTENT_ITEM_TYPE -> cv.getAsString(Email.ADDRESS)?.let { emails += DataItem(value = it, type = cv.getAsInteger(Email.TYPE) ?: Email.TYPE_OTHER, label = cv.getAsString(Email.LABEL)) }
                Website.CONTENT_ITEM_TYPE -> cv.getAsString(Website.URL)?.let { sites += DataItem(value = it, type = cv.getAsInteger(Website.TYPE) ?: Website.TYPE_OTHER) }
                Event.CONTENT_ITEM_TYPE -> cv.getAsString(Event.START_DATE)?.let { events += EventItem(date = it, type = cv.getAsInteger(Event.TYPE) ?: Event.TYPE_OTHER, label = cv.getAsString(Event.LABEL)) }
                StructuredPostal.CONTENT_ITEM_TYPE -> addresses += PostalItem(
                    street = cv.getAsString(StructuredPostal.STREET) ?: cv.getAsString(StructuredPostal.FORMATTED_ADDRESS).orEmpty(),
                    city = cv.getAsString(StructuredPostal.CITY).orEmpty(),
                    region = cv.getAsString(StructuredPostal.REGION).orEmpty(),
                    postcode = cv.getAsString(StructuredPostal.POSTCODE).orEmpty(),
                    country = cv.getAsString(StructuredPostal.COUNTRY).orEmpty(),
                    type = cv.getAsInteger(StructuredPostal.TYPE) ?: StructuredPostal.TYPE_HOME,
                )
                StructuredName.CONTENT_ITEM_TYPE -> d = d.copy(
                    given = cv.getAsString(StructuredName.GIVEN_NAME) ?: d.given,
                    family = cv.getAsString(StructuredName.FAMILY_NAME) ?: d.family,
                    middle = cv.getAsString(StructuredName.MIDDLE_NAME) ?: d.middle,
                    prefix = cv.getAsString(StructuredName.PREFIX) ?: d.prefix,
                    suffix = cv.getAsString(StructuredName.SUFFIX) ?: d.suffix,
                )
                Organization.CONTENT_ITEM_TYPE -> d = d.copy(company = cv.getAsString(Organization.COMPANY) ?: d.company, title = cv.getAsString(Organization.TITLE) ?: d.title)
                Nickname.CONTENT_ITEM_TYPE -> d = d.copy(nickname = cv.getAsString(Nickname.NAME) ?: d.nickname)
                Note.CONTENT_ITEM_TYPE -> d = d.copy(note = listOf(d.note, cv.getAsString(Note.NOTE).orEmpty()).filter { it.isNotBlank() }.joinToString("\n"))
            }
        }
        return d.copy(phones = phones, emails = emails, addresses = addresses, websites = sites, events = events)
    }

    private fun typeOf(intent: Intent, key: String, default: Int): Int {
        val v = intent.extras?.get(key) ?: return default
        return when (v) {
            is Int -> v
            is String -> v.toIntOrNull() ?: default
            else -> default
        }
    }

    /** Adds the prefill's multi-value rows to an existing contact draft ("add to existing"). */
    fun appendTo(existing: ContactDetails, add: ContactDetails): ContactDetails = existing.copy(
        phones = existing.phones + add.phones.filter { p -> existing.phones.none { app.parley.common.PhoneNumbers.matchKey(it.value) == app.parley.common.PhoneNumbers.matchKey(p.value) } },
        emails = existing.emails + add.emails.filter { e -> existing.emails.none { it.value.equals(e.value, true) } },
        websites = existing.websites + add.websites,
        addresses = existing.addresses + add.addresses,
        events = existing.events + add.events,
        company = existing.company.ifBlank { add.company },
        title = existing.title.ifBlank { add.title },
        note = listOf(existing.note, add.note).filter { it.isNotBlank() }.joinToString("\n"),
    )
}
