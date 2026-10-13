package app.parley.data

import androidx.room.withTransaction
import app.parley.common.PhoneIdentity
import app.parley.data.db.AppDatabase
import app.parley.data.db.NumberSimEntity
import app.parley.data.db.SpeedDialEntity
import app.parley.data.security.LineKeys
import app.parley.data.security.RecordCrypto
import app.parley.data.security.RecordSealing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Speed dial slots and the remembered SIM per number. Neither keeps a number readable at rest: a slot's number is
 * sealed ([RecordCrypto]), and a SIM choice is stored under the line's keyed fingerprint ([LineKeys]) with the line key
 * sealed beside it. Callers see plain numbers and line keys (backups among them, so a backup moves between phones and
 * is keyed again on restore). Values stored plain (by older versions, or while a key couldn't be used) still read, and
 * [resealPlain] seals them.
 */
class PrefsRepository(
    private val db: AppDatabase,
    private val crypto: RecordCrypto,
    private val lines: LineKeys,
    private val region: () -> String? = { null },
) : RecordSealing.Resealable {
    private val dao = db.prefsDao()

    val speedDials: Flow<List<SpeedDialEntity>> = dao.speedDials().map { l -> l.map { it.opened() } }.flowOn(Dispatchers.IO)

    /** Remembered SIMs with their line keys (a row whose sealed key can't be opened right now is left out). */
    val numberSims: Flow<List<NumberSimEntity>> = dao.allSims().map { l -> l.mapNotNull { it.opened() } }.flowOn(Dispatchers.IO)

    suspend fun speedDial(key: Int) = withContext(Dispatchers.IO) { dao.speedDial(key)?.opened() }

    suspend fun setSpeedDial(key: Int, number: String, label: String?) = withContext(Dispatchers.IO) {
        dao.setSpeedDial(SpeedDialEntity(key, crypto.sealText(number).orEmpty(), label))
    }

    suspend fun clearSpeedDial(key: Int) = dao.clearSpeedDial(key)

    /** The SIM remembered for this line; rows stored before the key migration are still found by their last digits. */
    suspend fun simFor(number: String): String? = withContext(Dispatchers.IO) {
        PhoneIdentity.lookupKeys(number, region()).firstNotNullOfOrNull { key ->
            listOfNotNull(lines.keyed(key), key).distinct().firstNotNullOfOrNull { dao.simFor(it) }
        }?.phoneAccountId
    }

    suspend fun setSimFor(number: String, accountId: String?) = withContext(Dispatchers.IO) {
        val keys = PhoneIdentity.lookupKeys(number, region())
        val key = keys.firstOrNull() ?: return@withContext
        db.withTransaction {
            // Every form goes (the old last-digits row too), so none can answer for this line again after the choice changed.
            dao.clearSims(lines.forms(keys))
            if (accountId != null) dao.setSim(stored(key, accountId))
        }
    }

    /** A remembered SIM from a backup ([key] a line key as [numberSims] gives it), keyed with this phone's key. */
    suspend fun restoreSim(key: String, accountId: String) = withContext(Dispatchers.IO) {
        if (key.isEmpty() || LineKeys.isKeyed(key)) return@withContext
        db.withTransaction {
            dao.clearSims(lines.forms(listOf(key)))
            dao.setSim(stored(key, accountId))
        }
    }

    /** [key]'s row as stored: keyed with the line key sealed beside it, or plain when a key can't be used now. */
    private fun stored(key: String, accountId: String): NumberSimEntity {
        val keyed = lines.keyed(key)
        val sealed = crypto.sealText(key)
        return if (keyed != null && keyed != key && crypto.isSealed(sealed)) NumberSimEntity(keyed, accountId, sealed) else NumberSimEntity(key, accountId)
    }

    private fun SpeedDialEntity.opened(): SpeedDialEntity = if (crypto.isSealed(number)) copy(number = crypto.openText(number).orEmpty()) else this

    private fun NumberSimEntity.opened(): NumberSimEntity? = when {
        !LineKeys.isKeyed(matchKey) -> this
        else -> crypto.openText(sealedKey)?.let { NumberSimEntity(it, phoneAccountId) }
    }

    /**
     * Seals speed dial numbers and keys SIM choices still stored plain, and keys again those keyed with a key since
     * replaced. False while something is left (a key couldn't be used).
     */
    override suspend fun resealPlain(): Boolean = withContext(Dispatchers.IO) {
        val left = dao.speedDialsNow().count { !sealSpeedDial(it) } + dao.allSimsNow().count { !keySim(it) }
        left == 0
    }

    /** Seals [e]'s number if it is plain; false when it stays plain for now. */
    private suspend fun sealSpeedDial(e: SpeedDialEntity): Boolean {
        if (e.number.isEmpty() || crypto.isSealed(e.number)) return true
        val sealed = crypto.sealText(e.number)
        if (sealed == null || !crypto.isSealed(sealed)) return false
        dao.sealSpeedDial(e.key, e.number, sealed)
        return true
    }

    /** Keys [e] if it is plain or keyed with a replaced key; false when it stays plain for now. */
    private suspend fun keySim(e: NumberSimEntity): Boolean {
        val wasKeyed = LineKeys.isKeyed(e.matchKey)
        // A sealed key that can't be opened is left as it is.
        val key = (if (wasKeyed) crypto.openText(e.sealedKey) else e.matchKey).orEmpty()
        if (key.isEmpty()) return true
        val row = stored(key, e.phoneAccountId)
        if (row.matchKey == e.matchKey) return wasKeyed
        db.withTransaction {
            // A choice already stored under the keyed form is newer: it stays.
            dao.addSimIfAbsent(row)
            dao.clearSim(e.matchKey)
        }
        return true
    }
}
