package app.parley.baselineprofile

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.provider.CallLog.Calls
import android.provider.ContactsContract
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Seeds a test device with the sizes the benchmarks are about: 3000 contacts and 3000 calls. Idempotent (only adds
 * what's missing) and recognisable: every seeded number starts with +1 555 01, a range reserved for fiction. Use a
 * test device or emulator only; `docs/PERFORMANCE_BENCHMARKS.md` explains how to remove the data again.
 */
object BenchmarkData {
    const val SIZE = 3000
    private const val PREFIX = "+155501"

    private fun number(i: Int) = PREFIX + "%05d".format(java.util.Locale.ROOT, i)

    fun ensure(size: Int = SIZE) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val own = instrumentation.targetContext.packageName
        // The benchmark APK's own permissions, granted through the shell.
        for (p in listOf("READ_CONTACTS", "WRITE_CONTACTS", "READ_CALL_LOG", "WRITE_CALL_LOG")) {
            instrumentation.uiAutomation.executeShellCommand("pm grant $own android.permission.$p").close()
        }
        val ctx = instrumentation.targetContext
        seedContacts(ctx, size)
        seedCalls(ctx, size)
    }

    /** The family name of the seeded contact the contact-page journey opens (seeded as "Ada Bench 0"). */
    const val CONTACT_NAME = "Bench 0"

    /** The lookup URI of the first seeded contact ([CONTACT_NAME]); the Contacts root when it isn't there yet. */
    fun contactUri(): android.net.Uri {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val c = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
        return ctx.contentResolver.query(c, cols, "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ?", arrayOf(number(0)), null)?.use {
            if (it.moveToFirst()) ContactsContract.Contacts.getLookupUri(it.getLong(0), it.getString(1)) else null
        } ?: ContactsContract.Contacts.CONTENT_URI
    }

    private fun count(ctx: Context, uri: android.net.Uri, column: String): Int =
        ctx.contentResolver.query(uri, arrayOf(column), "$column LIKE ?", arrayOf("$PREFIX%"), null)?.use { it.count } ?: 0

    private fun seedContacts(ctx: Context, size: Int) {
        val have = count(ctx, ContactsContract.CommonDataKinds.Phone.CONTENT_URI, ContactsContract.CommonDataKinds.Phone.NUMBER)
        for (chunk in (have until size).chunked(100)) {
            val ops = ArrayList<ContentProviderOperation>()
            for (i in chunk) {
                val back = ops.size
                ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, back)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, NAMES[i % NAMES.size])
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, "Bench $i")
                    .build()
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, back)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number(i))
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            }
            ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        }
    }

    private fun seedCalls(ctx: Context, size: Int) {
        val have = count(ctx, Calls.CONTENT_URI, Calls.NUMBER)
        val now = System.currentTimeMillis()
        val types = intArrayOf(Calls.INCOMING_TYPE, Calls.OUTGOING_TYPE, Calls.MISSED_TYPE)
        for (chunk in (have until size).chunked(200)) {
            val rows = chunk.map { i ->
                ContentValues().apply {
                    // Every third call from someone unknown (outside the seeded contacts), spread over two years.
                    put(Calls.NUMBER, if (i % 3 == 0) number(size + i) else number(i % size))
                    put(Calls.TYPE, types[i % types.size])
                    put(Calls.DATE, now - i * 20L * 60_000L)
                    put(Calls.DURATION, if (types[i % types.size] == Calls.MISSED_TYPE) 0 else (i % 600).toLong())
                    put(Calls.NEW, 0)
                    put(Calls.IS_READ, 1)
                }
            }.toTypedArray()
            ctx.contentResolver.bulkInsert(Calls.CONTENT_URI, rows)
        }
    }

    private val NAMES = listOf("Ada", "Grace", "Alan", "Katherine", "Linus", "Margaret", "Dennis", "Barbara", "Ken", "Frances", "Tim", "Radia")
}
