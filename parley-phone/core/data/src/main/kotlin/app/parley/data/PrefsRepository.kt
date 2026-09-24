package app.parley.data

import app.parley.common.PhoneNumbers
import app.parley.data.db.AppDatabase
import app.parley.data.db.NumberSimEntity
import app.parley.data.db.SpeedDialEntity
import kotlinx.coroutines.flow.Flow

/** Speed dial slots and the remembered SIM per number. */
class PrefsRepository(db: AppDatabase) {
    private val dao = db.prefsDao()

    val speedDials: Flow<List<SpeedDialEntity>> = dao.speedDials()
    val numberSims: Flow<List<NumberSimEntity>> = dao.allSims()

    suspend fun speedDial(key: Int) = dao.speedDial(key)
    suspend fun setSpeedDial(key: Int, number: String, label: String?) = dao.setSpeedDial(SpeedDialEntity(key, number, label))
    suspend fun clearSpeedDial(key: Int) = dao.clearSpeedDial(key)

    suspend fun simFor(number: String): String? = dao.simFor(PhoneNumbers.matchKey(number))?.phoneAccountId
    suspend fun setSimFor(number: String, accountId: String?) {
        val key = PhoneNumbers.matchKey(number)
        if (accountId == null) dao.clearSim(key) else dao.setSim(NumberSimEntity(key, accountId))
    }
}
