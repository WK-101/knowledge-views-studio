package app.parley.common.storage

/**
 * When daily upkeep gives a database's free pages back to the phone. Pruning (old calls, 30-day undo copies, the
 * screened-calls log) leaves free pages behind, and a SQLite file never shrinks on its own. Above [FREE_SHARE] of free
 * pages, an incremental vacuum returns them cheaply; a database that isn't in incremental mode yet is switched once
 * with a full vacuum, which also compacts it.
 */
object VacuumPolicy {
    enum class Step { NONE, INCREMENTAL, SWITCH_AND_VACUUM }

    /** SQLite's `PRAGMA auto_vacuum` value for incremental mode. */
    const val INCREMENTAL_MODE = 2

    const val FREE_SHARE = 0.20

    fun decide(autoVacuum: Int, freePages: Long, pageCount: Long): Step = when {
        pageCount <= 0 || freePages <= 0 -> Step.NONE
        freePages.toDouble() / pageCount <= FREE_SHARE -> Step.NONE
        autoVacuum == INCREMENTAL_MODE -> Step.INCREMENTAL
        else -> Step.SWITCH_AND_VACUUM
    }
}
