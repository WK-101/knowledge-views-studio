package app.parley.common.backup

import app.parley.common.backup.Fixtures.contact
import app.parley.common.backup.Fixtures.phone
import app.parley.common.backup.Fixtures.photo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SnapshotLogTest {
    private fun h(n: Int) = n.toString(16).padStart(64, '0')
    private val a = h(0xa)
    private val b = h(0xb)
    private val c = h(0xc)

    @Test fun onlyChangesAreStoredAndEverySnapshotReadsBackWhole() {
        val log = SnapshotLog()
        assertTrue(log.add(100, mapOf("mum" to a, "dad" to b)))
        assertFalse("an unchanged day stores nothing", log.add(200, mapOf("mum" to a, "dad" to b)))
        assertTrue(log.add(300, mapOf("mum" to c)))
        assertEquals(4, log.entryCount) // mum a, dad b, mum c, dad gone
        assertEquals(mapOf("mum" to a, "dad" to b), log.snapshot(200)!!.contacts)
        assertEquals(mapOf("mum" to c), log.snapshot(300)!!.contacts)
        assertNull(log.snapshot(150))
        assertEquals(listOf(100L to b, 300L to null), log.history("dad"))
        assertEquals(b, log.lastPresent("dad"))
        assertEquals(a, log.lastPresent("mum", atOrBefore = 299))
        assertNull(log.lastPresent("mum", atOrBefore = 99))
    }

    @Test fun retainKeepsEveryKeptSnapshotExactly() {
        val days = listOf(
            100L to mapOf("x" to a),
            200L to mapOf("x" to b, "y" to a),
            300L to mapOf("y" to a),
            400L to mapOf("x" to c, "y" to b),
            500L to mapOf("x" to c, "y" to b),
        )
        for (keep in listOf(setOf(500L), setOf(100L, 500L), setOf(200L, 400L), setOf(300L), setOf(100L, 200L, 300L, 400L))) {
            val log = SnapshotLog()
            days.forEach { (t, m) -> log.add(t, m) }
            log.retain(keep)
            assertEquals(keep.sorted(), log.timestamps)
            days.filter { it.first in keep }.forEach { (t, m) -> assertEquals("keep $keep at $t", m, log.snapshot(t)!!.contacts) }
            // No version stands for nothing: no leading "gone", no repeat of the same record.
            for (k in listOf("x", "y")) {
                val hist = log.history(k)
                assertTrue(hist.firstOrNull()?.second != null || hist.isEmpty())
                assertTrue(hist.zipWithNext().none { (p, q) -> p.second == q.second })
            }
        }
    }

    @Test fun movingTheLastSnapshotForwardKeepsItsContent() {
        val log = SnapshotLog()
        log.add(100, mapOf("x" to a))
        log.add(200, mapOf("x" to b))
        log.add(300, mapOf("x" to b))
        log.retain(log.timestamps.toSet() - 200L)
        assertEquals(listOf(100L, 300L), log.timestamps)
        assertEquals(listOf(100L to a, 300L to b), log.history("x"))
    }

    @Test fun garbageIsFoundFromTheIndexAloneAndPurgeForgetsAContact() {
        val p = h(0x99)
        val log = SnapshotLog()
        log.add(100, mapOf("x" to a, "y" to b), photos = mapOf(a to listOf(p)))
        assertEquals(setOf(a, b, p), log.referencedBlobs())
        log.add(200, mapOf("x" to c, "y" to b))
        log.retain(setOf(200L))
        assertEquals("the old version and its photo are no longer needed", setOf(c, b), log.referencedBlobs())
        assertTrue(log.purge("y"))
        assertEquals(setOf(c), log.referencedBlobs())
        assertFalse(log.purge("y"))
    }

    @Test fun spansEndWithTheLastSnapshotThatHadTheVersion() {
        val log = SnapshotLog()
        log.add(100, mapOf("x" to a))
        log.add(200, mapOf("x" to a))
        log.add(300, mapOf("x" to b))
        log.add(400, emptyMap())
        assertEquals(
            setOf(SnapshotLog.Span("x", a, 200), SnapshotLog.Span("x", b, 300)),
            log.spans().toSet(),
        )
    }

    @Test fun aDamagedSnapshotChangesNothing() {
        val log = SnapshotLog()
        log.add(100, mapOf("x" to a))
        try {
            log.add(200, mapOf("x" to "not a hash"))
            fail()
        } catch (_: BackupIntegrityException) {
        }
        assertEquals(listOf(100L), log.timestamps)
        assertEquals(1, log.entryCount)
    }

    /** The format on disk, byte for byte: a change to it needs a new FORMAT and a migration. */
    @Test fun theFormatIsPinned() {
        val log = SnapshotLog()
        log.add(1, mapOf("k" to a), photos = mapOf(a to listOf(b)))
        log.add(2, emptyMap())
        val expected = "50544d4c01" + // "PTML", format 1
            "00000002" + "0000000000000001" + "0000000000000002" + // two times
            "00000001" + "00016b" + "00000002" + // one key, "k", two versions
            "0000000000000001" + "01" + a + // from 1: record a
            "0000000000000002" + "00" + // from 2: gone
            "00000001" + a + "00000001" + b // record a has photo b
        assertEquals(expected, log.encode().joinToString("") { "%02x".format(it) })
        val back = SnapshotLog.decode(log.encode())
        assertArrayEquals(log.encode(), back.encode())
        assertEquals(setOf(a, b), back.referencedBlobs())
    }

    @Test fun damagedBytesAreReportedNotTrusted() {
        val bytes = SnapshotLog().apply { add(1, mapOf("k" to a)) }.encode()
        for (bad in listOf(bytes.copyOf(bytes.size - 3), bytes + byteArrayOf(0), byteArrayOf(1, 2, 3, 4, 5))) {
            try {
                SnapshotLog.decode(bad)
                fail()
            } catch (_: BackupIntegrityException) {
            }
        }
    }

    /**
     * The per-snapshot files versions before 5.4 wrote, byte for byte as they wrote them, move over into the same
     * snapshots.
     */
    @Test fun oldIndexFilesMoveOverUnchanged() {
        val old = listOf(
            """{"timestamp":100,"contacts":{"dad":"$b","mum":"$a"}}""",
            """{"timestamp":200,"contacts":{"mum":"$c"}}""",
            """{"timestamp":300,"contacts":{"bob":"$b","mum":"$c"}}""",
        )
        // The 5.3 writer produced exactly these bytes (the reader below is the same code that read them).
        assertEquals(old[0], String(SnapshotIndex(100, mapOf("mum" to a, "dad" to b)).toBytes()))
        val indexes = old.map { SnapshotIndex.fromBytes(it.toByteArray()) }
        val log = SnapshotLog.fromIndexes(indexes.asSequence())
        assertEquals(listOf(100L, 200L, 300L), log.timestamps)
        indexes.forEach { assertEquals(it, log.snapshot(it.timestamp)) }
    }

    @Test fun recordsFromTheWriterCarryTheirPhotos() {
        val store = InMemoryBlobStore()
        val pic = ByteArray(500) { it.toByte() }
        val r = SnapshotWriter(store).write(100, listOf(contact("mum", "Jane", phone("+44 7700 900001"), photo(pic)), contact("dad", "John")))
        val log = SnapshotLog()
        log.add(100, r.index.contacts, r.photos)
        assertEquals(store.keys, log.referencedBlobs())
    }

    /**
     * Scale: half a year of daily snapshots of 5,000 and 20,000 contacts with 1% of them changing a day (and a few
     * added and deleted). Measured numbers are printed for docs/PERFORMANCE_BENCHMARKS.md; the bounds are generous,
     * there to catch a regression to one full map per day.
     */
    @Test fun halfAYearOfSnapshotsStaysSmall() {
        for (n in listOf(5_000, 20_000)) {
            val rnd = java.util.Random(n.toLong())
            fun fresh() = (0 until 4).joinToString("") { java.lang.Long.toHexString(rnd.nextLong()).padStart(16, '0') }
            val now = HashMap<String, String>()
            repeat(n) { now["0r$it-${fresh().take(12)}"] = fresh() }
            val log = SnapshotLog()
            var biggestDay = 0
            var buildNs = 0L
            for (day in 0 until 180) {
                val keys = now.keys.toList()
                repeat(n / 100) { now[keys[rnd.nextInt(keys.size)]] = fresh() }
                repeat(n / 1000) { now.remove(keys[rnd.nextInt(keys.size)]); now["new$day-$it"] = fresh() }
                val t0 = System.nanoTime()
                log.add(day * 86_400_000L, now)
                buildNs += System.nanoTime() - t0
                // The old format wrote the whole map every day; its size barely moves, so a few days measure it.
                if (day % 60 == 0) biggestDay = maxOf(biggestDay, SnapshotIndex(day * 86_400_000L, now).toBytes().size)
            }
            val oldBytes = biggestDay * 180L
            val buildMs = buildNs / 1_000_000 / 180
            val bytes = log.encode()
            var t = System.nanoTime()
            val back = SnapshotLog.decode(bytes)
            val decodeMs = (System.nanoTime() - t) / 1_000_000
            t = System.nanoTime()
            val first = back.snapshot(back.timestamps.first())!!
            val last = back.snapshot(back.timestamps.last())!!
            val twoSnapshotsMs = (System.nanoTime() - t) / 1_000_000
            t = System.nanoTime()
            repeat(1_000) { back.history(now.keys.first()) }
            val historyUs = (System.nanoTime() - t) / 1_000 / 1_000
            t = System.nanoTime()
            back.retain(back.timestamps.drop(1).toSet())
            val referenced = back.referencedBlobs()
            val pruneMs = (System.nanoTime() - t) / 1_000_000
            println(
                "SnapshotLog n=$n: entries=${log.entryCount} file=${bytes.size / 1024} KiB (old format ${oldBytes / 1024 / 1024} MiB, " +
                    "${biggestDay / 1024} KiB a day) perDay=${buildMs}ms decode=${decodeMs}ms twoSnapshots=${twoSnapshotsMs}ms " +
                    "history=${historyUs}us prune+gc=${pruneMs}ms distinctRecords=${referenced.size}",
            )
            assertEquals(now.size, last.contacts.size)
            assertTrue(first.contacts.isNotEmpty())
            // About 3 versions per contact over the half-year; the old format kept 180 full maps.
            assertTrue(log.entryCount < n * 4)
            assertTrue("index ${bytes.size} vs old $oldBytes", bytes.size * 50L < oldBytes)
            assertTrue("decode took $decodeMs ms", decodeMs < 5_000)
        }
    }
}
