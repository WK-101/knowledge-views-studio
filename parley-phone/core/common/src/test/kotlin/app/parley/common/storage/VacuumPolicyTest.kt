package app.parley.common.storage

import app.parley.common.storage.VacuumPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class VacuumPolicyTest {
    @Test fun aFewFreePagesStay() {
        assertEquals(Step.NONE, VacuumPolicy.decide(autoVacuum = 2, freePages = 20, pageCount = 100))
        assertEquals(Step.NONE, VacuumPolicy.decide(autoVacuum = 0, freePages = 0, pageCount = 100))
        assertEquals(Step.NONE, VacuumPolicy.decide(autoVacuum = 0, freePages = 5, pageCount = 0))
    }

    @Test fun anIncrementalDatabaseGivesItsFreePagesBack() {
        assertEquals(Step.INCREMENTAL, VacuumPolicy.decide(autoVacuum = 2, freePages = 21, pageCount = 100))
    }

    @Test fun anyOtherDatabaseIsSwitchedOnce() {
        assertEquals(Step.SWITCH_AND_VACUUM, VacuumPolicy.decide(autoVacuum = 0, freePages = 50, pageCount = 100))
        assertEquals(Step.SWITCH_AND_VACUUM, VacuumPolicy.decide(autoVacuum = 1, freePages = 50, pageCount = 100))
    }
}
