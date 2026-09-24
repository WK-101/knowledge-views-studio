package app.parley.data

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import app.parley.common.CallEntry
import app.parley.common.ContactSummary
import app.parley.common.PhoneNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class HealthKind { NUMBER_AS_NAME, EMPTY, NO_COUNTRY_CODE, SHARED_NUMBER, TITLE_IS_COMPANY, STALE }

data class HealthIssue(
    val kind: HealthKind,
    val contactId: Long,
    val lookupKey: String,
    val name: String,
    val detail: String,
    /** Data row to rewrite and its suggested new value (automatic fixes). */
    val dataId: Long? = null,
    val suggested: String? = null,
)

/** Finds common address-book problems and fixes the safe ones in one tap. All local. */
class HealthScanner(private val context: Context) {
    private val cr = context.contentResolver

    suspend fun scan(contacts: List<ContactSummary>, calls: List<CallEntry>, countryIso: String): List<HealthIssue> = withContext(Dispatchers.IO) {
        val out = ArrayList<HealthIssue>()
        val byKey = HashMap<String, MutableList<ContactSummary>>()
        contacts.forEach { c -> c.phones.forEach { p -> PhoneNumbers.matchKey(p.number).takeIf { it.length >= 7 }?.let { byKey.getOrPut(it) { ArrayList() } += c } } }
        val lastCall = HashMap<String, Long>()
        calls.forEach { e -> lastCall.putIfAbsent(PhoneNumbers.matchKey(e.number), e.date) }
        val twoYears = System.currentTimeMillis() - 2L * 365 * 86_400_000L

        for (c in contacts) {
            val digitsOnly = c.displayName.all { it.isDigit() || it in "+-() " }
            if (digitsOnly && c.phones.isNotEmpty()) out += HealthIssue(HealthKind.NUMBER_AS_NAME, c.id, c.lookupKey, c.displayName, context.getString(R.string.data_health_number_as_name))
            if (c.phones.isEmpty() && c.emails.isEmpty() && digitsOnly) out += HealthIssue(HealthKind.EMPTY, c.id, c.lookupKey, c.displayName, context.getString(R.string.data_health_empty))
            c.phones.map { PhoneNumbers.matchKey(it.number) }.distinct().forEach { k ->
                val owners = byKey[k].orEmpty().distinctBy { it.id }
                if (owners.size > 1 && owners.first().id == c.id) {
                    out += HealthIssue(HealthKind.SHARED_NUMBER, c.id, c.lookupKey, c.displayName, context.getString(R.string.data_health_shared, owners.drop(1).joinToString { it.displayName }))
                }
            }
            if (c.phones.isNotEmpty() && c.phones.none { p -> (lastCall[PhoneNumbers.matchKey(p.number)] ?: 0L) > twoYears } && calls.isNotEmpty()) {
                val oldest = calls.lastOrNull()?.date ?: Long.MAX_VALUE
                if (oldest < twoYears) out += HealthIssue(HealthKind.STALE, c.id, c.lookupKey, c.displayName, context.getString(R.string.data_health_stale))
            }
        }
        // Numbers saved without a country code (fixable when we know the country).
        cr.safeQuery(Phone.CONTENT_URI, arrayOf(Phone._ID, Phone.CONTACT_ID, Phone.NUMBER, Phone.DISPLAY_NAME_PRIMARY, Phone.LOOKUP_KEY))?.use { q ->
            while (q.moveToNext()) {
                val n = q.getString(2) ?: continue
                val clean = PhoneNumbers.clean(n)
                if (clean.startsWith("+") || clean.startsWith("00") || clean.length < 7 || PhoneNumbers.isServiceCode(n)) continue
                val e164 = PhoneNumbers.toE164(n, countryIso) ?: continue
                out += HealthIssue(HealthKind.NO_COUNTRY_CODE, q.getLong(1), q.getString(4).orEmpty(), q.getString(3) ?: n, context.getString(R.string.data_health_country_code, app.parley.data.DataBidi.ltr(n), app.parley.data.DataBidi.ltr(e164)), q.getLong(0), e164)
            }
        }
        // Job title identical to the company (a common import bug).
        cr.safeQuery(Data.CONTENT_URI, arrayOf(Data._ID, Data.CONTACT_ID, Organization.COMPANY, Organization.TITLE, Data.DISPLAY_NAME_PRIMARY, Data.LOOKUP_KEY), "${Data.MIMETYPE}=?", arrayOf(Organization.CONTENT_ITEM_TYPE))?.use { q ->
            while (q.moveToNext()) {
                val company = q.getString(2)?.trim().orEmpty()
                val title = q.getString(3)?.trim().orEmpty()
                if (company.isNotEmpty() && company.equals(title, true)) {
                    out += HealthIssue(HealthKind.TITLE_IS_COMPANY, q.getLong(1), q.getString(5).orEmpty(), q.getString(4) ?: company, context.getString(R.string.data_health_title_company, title), q.getLong(0), "")
                }
            }
        }
        out
    }

    /** Applies automatic fixes (country codes, duplicated titles). Returns rows changed. */
    suspend fun fix(issues: List<HealthIssue>): Int = withContext(Dispatchers.IO) {
        val ops = ArrayList<ContentProviderOperation>()
        issues.forEach { i ->
            val id = i.dataId ?: return@forEach
            when (i.kind) {
                HealthKind.NO_COUNTRY_CODE -> ops += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Data.CONTENT_URI, id)).withValue(Phone.NUMBER, i.suggested).build()
                HealthKind.TITLE_IS_COMPANY -> ops += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Data.CONTENT_URI, id)).withValue(Organization.TITLE, null).build()
                else -> Unit
            }
        }
        ops.chunked(300).forEach { cr.applyBatch(ContactsContract.AUTHORITY, ArrayList(it)) }
        ops.size
    }
}
