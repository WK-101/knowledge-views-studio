package app.parley.data.history

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reading the whole archive a page at a time: keyset pages start where the last one ended, OFFSET pages walk every
 * row before them again. Measured on 100,000 rows (the audit's scale), printed for docs/PERFORMANCE_BENCHMARKS.md; the
 * test asserts what doesn't depend on the machine: every row once, and a plan that searches the date index.
 */
@RunWith(RobolectricTestRunner::class)
class ArchiveKeysetSpeedTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: HistoryDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, HistoryDatabase::class.java).allowMainThreadQueries().build()
        runBlocking {
            (0 until ROWS).chunked(5_000).forEach { chunk ->
                db.dao().insert(
                    chunk.map { i ->
                        // Ten calls share each date, so pages end inside runs of equal dates.
                        ArchivedCallEntity(
                            dedupeKey = "d$i", personKey = "p${i % 500}", date = 1_600_000_000_000L + (i / 10) * 60_000L,
                            durationSec = i.toLong(), type = 1, blob = ByteArray(120), archivedAt = 0,
                        )
                    },
                )
            }
        }
    }

    @After fun tearDown() = db.close()

    @Test fun keysetReadsEveryRowOnceThroughTheDateIndex() = runBlocking {
        val dao = db.dao()
        var t = System.nanoTime()
        val seen = HashSet<Long>(ROWS * 2)
        var page = dao.firstPage(PAGE)
        while (page.isNotEmpty()) {
            page.forEach { seen += it.id }
            if (page.size < PAGE) break
            page = dao.pageBefore(page.last().date, page.last().id, PAGE)
        }
        val keysetMs = (System.nanoTime() - t) / 1_000_000
        assertEquals(ROWS, seen.size)

        // The query the archive used before 5.5.
        t = System.nanoTime()
        var offset = 0
        var read = 0
        val sql = db.openHelper.readableDatabase
        do {
            val query = "SELECT * FROM archived_calls ORDER BY date DESC, id DESC LIMIT $PAGE OFFSET $offset"
            val n = sql.query(query).use { c -> c.count.also { while (c.moveToNext()) Unit } }
            read += n
            offset += n
        } while (n == PAGE)
        val offsetMs = (System.nanoTime() - t) / 1_000_000
        assertEquals(ROWS, read)

        t = System.nanoTime()
        val one = dao.byPersons(listOf("p7"))
        val personMs = (System.nanoTime() - t) / 1_000_000
        assertEquals(ROWS / 500, one.size)
        println("Archive of $ROWS rows read in pages of $PAGE: keyset $keysetMs ms, OFFSET $offsetMs ms; one person's ${one.size} rows by index $personMs ms")
        // The times above are a benchmark (a busy machine is slow either way). What makes keyset pages cheap is the
        // plan: each page is a search in the date index, starting where the last one ended, with no sort of its own.
        val plan = sql.query(
            "EXPLAIN QUERY PLAN SELECT * FROM archived_calls WHERE date <= ? AND (date < ? OR id < ?) ORDER BY date DESC, id DESC LIMIT ?",
            arrayOf<Any>(1L, 1L, 1L, PAGE),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) } }.joinToString(" | ")
        println("Keyset page plan: $plan")
        assertTrue(plan, plan.contains("SEARCH") && plan.contains("index_archived_calls_date"))
        assertTrue(plan, !plan.contains("TEMP B-TREE"))
    }

    private companion object {
        const val ROWS = 100_000
        const val PAGE = 500
    }
}
