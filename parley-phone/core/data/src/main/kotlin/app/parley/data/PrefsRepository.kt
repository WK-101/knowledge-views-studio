package app.parley.data

import app.parley.common.PhoneIdentity
import app.parley.data.db.AppDatabase
import app.parley.data.db.NumberSimEntity
import app.parley.data.db.SpeedDialEntity
import kotlinx.coroutines.flow.Flow

/** Speed dial slots and the remembered SIM per number. */
class PrefsRepository(db: AppDatabase, private val region: () -> String? = { null }) {
    private val dao = db.prefsDao()

    val speedDials: Flow<List<SpeedDialEntity>> = dao.speedDials()
    val numberSims: Flow<List<NumberSimEntity>> = dao.allSims()

    suspend fun speedDial(key: Int) = dao.speedDial(key)
    suspend fun setSpeedDial(key: Int, number: String, label: String?) = dao.setSpeedDial(SpeedDialEntity(key, number, label))
    suspend fun clearSpeedDial(key: Int) = dao.clearSpeedDial(key)

    /** The SIM remembered for this line; rows stored before the key migration are still found by their last digits. */
    suspend fun simFor(number: String): String? =
        PhoneIdentity.lookupKeys(number, region()).firstNotNullOfOrNull { dao.simFor(it) }?.phoneAccountId

    suspend fun setSimFor(number: String, accountId: String?) {
        val keys = PhoneIdentity.lookupKeys(number, region())
        // The old last-digits row goes, so it can't answer for this line again after the choice changed.
        keys.drop(1).forEach { dao.clearSim(it) }
        val key = keys.firstOrNull() ?: return
        if (accountId == null) dao.clearSim(key) else dao.setSim(NumberSimEntity(key, accountId))
    }
}
