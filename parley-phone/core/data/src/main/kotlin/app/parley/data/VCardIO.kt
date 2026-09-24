package app.parley.data

import android.content.ContentProviderOperation
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import app.parley.common.ContactSummary
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.parameter.AddressType
import ezvcard.parameter.EmailType
import ezvcard.parameter.TelephoneType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

/** vCard import (ez-vcard parser) and export (the platform's own vCard composer). */
class VCardIO(private val context: Context, private val contacts: ContactsRepository) {
    private val cr = context.contentResolver

    /** Writes every given contact as one .vcf file. Returns how many were exported. */
    suspend fun export(target: Uri, list: List<ContactSummary>, progress: (Int, Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        var n = 0
        cr.openOutputStream(target, "wt")?.use { out ->
            list.forEachIndexed { i, c ->
                if (c.lookupKey.isEmpty()) return@forEachIndexed
                try {
                    cr.openAssetFileDescriptor(contacts.vcardUri(c.lookupKey), "r")?.use { fd ->
                        fd.createInputStream().use { it.copyTo(out) }
                        n++
                    }
                } catch (_: Exception) {
                }
                if (i % 25 == 0) progress(i, list.size)
            }
        }
        n
    }

    /** Imports all vCards from [source] into [account]. Returns how many were imported. */
    suspend fun import(source: Uri, account: AccountRef, progress: (Int, Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        val cards: List<VCard> = cr.openInputStream(source)?.use { input ->
            Ezvcard.parse(InputStreamReader(input, Charsets.UTF_8)).all()
        }.orEmpty()
        var imported = 0
        cards.chunked(20).forEach { chunk ->
            val ops = ArrayList<ContentProviderOperation>()
            chunk.forEach { card -> addOps(ops, card, account) }
            if (ops.isNotEmpty()) {
                cr.applyBatch(ContactsContract.AUTHORITY, ops)
                imported += chunk.size
            }
            progress(imported, cards.size)
        }
        imported
    }

    private fun addOps(ops: ArrayList<ContentProviderOperation>, card: VCard, account: AccountRef) {
        val base = ops.size
        ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_TYPE, account.type)
            .withValue(RawContacts.ACCOUNT_NAME, account.name)
            .build()
        fun row(mime: String, vararg pairs: Pair<String, Any?>) {
            val b = ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, base)
                .withValue(Data.MIMETYPE, mime)
            pairs.forEach { (k, v) -> b.withValue(k, v) }
            ops += b.build()
        }

        val n = card.structuredName
        val fn = card.formattedName?.value
        if (n != null || fn != null) {
            row(
                StructuredName.CONTENT_ITEM_TYPE,
                StructuredName.DISPLAY_NAME to fn,
                StructuredName.GIVEN_NAME to n?.given,
                StructuredName.FAMILY_NAME to n?.family,
                StructuredName.MIDDLE_NAME to n?.additionalNames?.joinToString(" ")?.ifEmpty { null },
                StructuredName.PREFIX to n?.prefixes?.joinToString(" ")?.ifEmpty { null },
                StructuredName.SUFFIX to n?.suffixes?.joinToString(" ")?.ifEmpty { null },
            )
        }
        card.nicknames.flatMap { it.values }.firstOrNull()?.let { row(Nickname.CONTENT_ITEM_TYPE, Nickname.NAME to it) }
        card.telephoneNumbers.forEach { t ->
            val number = t.text ?: t.uri?.number ?: return@forEach
            val types = t.types
            val type = when {
                TelephoneType.CELL in types -> Phone.TYPE_MOBILE
                TelephoneType.WORK in types && TelephoneType.FAX in types -> Phone.TYPE_FAX_WORK
                TelephoneType.HOME in types && TelephoneType.FAX in types -> Phone.TYPE_FAX_HOME
                TelephoneType.WORK in types -> Phone.TYPE_WORK
                TelephoneType.HOME in types -> Phone.TYPE_HOME
                TelephoneType.PAGER in types -> Phone.TYPE_PAGER
                else -> Phone.TYPE_MOBILE
            }
            row(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to number, Phone.TYPE to type, Phone.IS_PRIMARY to if (t.pref == 1) 1 else 0)
        }
        card.emails.forEach { e ->
            val type = when {
                EmailType.WORK in e.types -> Email.TYPE_WORK
                EmailType.HOME in e.types -> Email.TYPE_HOME
                else -> Email.TYPE_OTHER
            }
            row(Email.CONTENT_ITEM_TYPE, Email.ADDRESS to e.value, Email.TYPE to type)
        }
        card.addresses.forEach { a ->
            val type = when {
                AddressType.WORK in a.types -> StructuredPostal.TYPE_WORK
                AddressType.HOME in a.types -> StructuredPostal.TYPE_HOME
                else -> StructuredPostal.TYPE_OTHER
            }
            row(
                StructuredPostal.CONTENT_ITEM_TYPE,
                StructuredPostal.STREET to a.streetAddress,
                StructuredPostal.CITY to a.locality,
                StructuredPostal.REGION to a.region,
                StructuredPostal.POSTCODE to a.postalCode,
                StructuredPostal.COUNTRY to a.country,
                StructuredPostal.TYPE to type,
            )
        }
        card.organization?.let { o ->
            row(Organization.CONTENT_ITEM_TYPE, Organization.COMPANY to o.values.firstOrNull(), Organization.TITLE to card.titles.firstOrNull()?.value)
        }
        card.urls.forEach { u -> row(Website.CONTENT_ITEM_TYPE, Website.URL to u.value, Website.TYPE to Website.TYPE_OTHER) }
        card.notes.firstOrNull()?.value?.let { row(Note.CONTENT_ITEM_TYPE, Note.NOTE to it) }
        card.birthday?.let { b ->
            val date = b.date?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(it) }
                ?: b.partialDate?.let { p -> if (p.month != null && p.date != null) "--%02d-%02d".format(p.month, p.date) else null }
                ?: b.text
            if (date != null) row(Event.CONTENT_ITEM_TYPE, Event.START_DATE to date, Event.TYPE to Event.TYPE_BIRTHDAY)
        }
        card.photos.firstOrNull()?.data?.let { bytes ->
            if (bytes.size < 800_000) row(Photo.CONTENT_ITEM_TYPE, Photo.PHOTO to bytes)
        }
    }
}
