package app.parley.data.people

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.SimPhonebookContract
import android.telephony.SubscriptionManager
import app.parley.common.people.SimEntry
import app.parley.common.people.SimFit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One SIM card's phonebook (ADN), with its limits when the SIM reports them. */
data class SimCard(
    val subscriptionId: Int,
    val label: String,
    val nameMax: Int = SimFit.DEFAULT_NAME_MAX,
    val numberMax: Int = SimFit.DEFAULT_NUMBER_MAX,
    /** Free entries, or -1 when unknown. */
    val free: Int = -1,
)

/**
 * SIM phonebook access: `SimPhonebookContract` on Android 12+, the legacy `content://icc/adn` provider on 10–11.
 * Both need only READ_CONTACTS / WRITE_CONTACTS; listing SIMs uses READ_PHONE_STATE, which the phone-app role
 * already grants. Every call degrades to "no SIM" instead of failing when something is not allowed.
 */
@SuppressLint("MissingPermission")
class SimContacts(private val context: Context) {
    private val cr = context.contentResolver

    suspend fun cards(): List<SimCard> = withContext(Dispatchers.IO) {
        val subs = try {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        subs.map { info ->
            val label = info.displayName?.toString()?.ifBlank { null } ?: context.getString(app.parley.data.R.string.data_sim_slot, info.simSlotIndex + 1)
            if (Build.VERSION.SDK_INT >= 31) limits(info.subscriptionId, label) else SimCard(info.subscriptionId, label)
        }
    }

    private fun limits(subId: Int, label: String): SimCard {
        if (Build.VERSION.SDK_INT < 31) return SimCard(subId, label)
        return try {
            cr.query(
                SimPhonebookContract.ElementaryFiles.getItemUri(subId, SimPhonebookContract.ElementaryFiles.EF_ADN),
                arrayOf(
                    SimPhonebookContract.ElementaryFiles.NAME_MAX_LENGTH,
                    SimPhonebookContract.ElementaryFiles.PHONE_NUMBER_MAX_LENGTH,
                    SimPhonebookContract.ElementaryFiles.MAX_RECORDS,
                    SimPhonebookContract.ElementaryFiles.RECORD_COUNT,
                ),
                null, null,
            )?.use { c ->
                if (!c.moveToFirst()) null else SimCard(
                    subId, label,
                    nameMax = c.getInt(0).takeIf { it > 0 } ?: SimFit.DEFAULT_NAME_MAX,
                    numberMax = c.getInt(1).takeIf { it > 0 } ?: SimFit.DEFAULT_NUMBER_MAX,
                    free = if (c.getInt(2) > 0) c.getInt(2) - c.getInt(3) else -1,
                )
            } ?: SimCard(subId, label)
        } catch (_: Exception) {
            SimCard(subId, label)
        }
    }

    /** Bytes [name] takes on this SIM (the SIM's own encoder on 12+, the GSM estimate otherwise). */
    fun encodedLength(name: String): Int {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                return SimPhonebookContract.SimRecords.getEncodedNameLength(cr, name)
            } catch (_: Exception) {
            }
        }
        return SimFit.gsmLength(name)
    }

    suspend fun read(card: SimCard): List<SimEntry> = withContext(Dispatchers.IO) {
        val out = ArrayList<SimEntry>()
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                cr.query(
                    SimPhonebookContract.SimRecords.getContentUri(card.subscriptionId, SimPhonebookContract.ElementaryFiles.EF_ADN),
                    arrayOf(SimPhonebookContract.SimRecords.NAME, SimPhonebookContract.SimRecords.PHONE_NUMBER), null, null,
                )?.use { c -> while (c.moveToNext()) entry(c.getString(0), c.getString(1))?.let(out::add) }
            } else {
                cr.query(legacyUri(card.subscriptionId), null, null, null, null)?.use { c ->
                    val n = c.getColumnIndex("name")
                    val p = c.getColumnIndex("number")
                    while (c.moveToNext()) entry(if (n >= 0) c.getString(n) else null, if (p >= 0) c.getString(p) else null)?.let(out::add)
                }
            }
        } catch (_: Exception) {
        }
        out
    }

    /** Writes one entry. Returns null on success, or a reason. */
    suspend fun write(card: SimCard, e: SimEntry): String? = withContext(Dispatchers.IO) {
        try {
            val uri = if (Build.VERSION.SDK_INT >= 31) {
                cr.insert(
                    SimPhonebookContract.SimRecords.getContentUri(card.subscriptionId, SimPhonebookContract.ElementaryFiles.EF_ADN),
                    ContentValues().apply {
                        put(SimPhonebookContract.SimRecords.NAME, e.name)
                        put(SimPhonebookContract.SimRecords.PHONE_NUMBER, e.number)
                    },
                )
            } else {
                cr.insert(legacyUri(card.subscriptionId), ContentValues().apply { put("tag", e.name); put("number", e.number) })
            }
            if (uri == null) context.getString(app.parley.data.R.string.data_sim_full) else null
        } catch (ex: SecurityException) {
            context.getString(app.parley.data.R.string.data_sim_not_allowed)
        } catch (ex: Exception) {
            ex.message ?: context.getString(app.parley.data.R.string.data_sim_rejected)
        }
    }

    private fun legacyUri(subId: Int): Uri =
        if (subId >= 0) Uri.parse("content://icc/adn/subId/$subId") else Uri.parse("content://icc/adn")

    private fun entry(name: String?, number: String?): SimEntry? {
        val n = number?.trim().orEmpty()
        if (n.isEmpty()) return null
        return SimEntry(name?.trim().orEmpty().ifEmpty { n }, n)
    }
}
