package app.parley.common.backup

import app.parley.common.backup.Fixtures.contact
import app.parley.common.backup.Fixtures.phone
import app.parley.common.backup.Fixtures.photo
import app.parley.common.record.ContactRecord
import app.parley.common.record.Mime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Restore planning compares two large address books by photo hash, holding no photo bytes, and gets them back. */
class PhotoRefsTest {
    private fun bytes(seed: Int, size: Int = 4_096) = ByteArray(size) { ((it * 31 + seed * 7) % 251).toByte() }

    private fun person(i: Int, photoSeed: Int?) = contact(
        "k$i", "Person $i",
        *listOfNotNull(phone("+1 555 01${"%05d".format(i)}"), photoSeed?.let { photo(bytes(it)) }).toTypedArray(),
    )

    private fun ContactRecord.blobs() = raws.flatMap { it.rows }.mapNotNull { it.blob }

    @Test fun aLightRecordKeepsTheHashAndComesBackWhole() {
        val full = person(1, photoSeed = 1)
        val light = PhotoRefs.light(full)
        assertTrue(light.blobs().isEmpty())
        val row = light.raws.single().rows.single { it.mimeType == Mime.PHOTO }
        assertEquals(RecordJson.sha256Hex(bytes(1)), PhotoRefs.hashOf(row))
        val photos = mapOf(RecordJson.sha256Hex(bytes(1)) to bytes(1))
        assertEquals(full, PhotoRefs.filled(light, photos::get))
        // A record without photos is passed through as it is.
        val plain = person(2, photoSeed = null)
        assertTrue(PhotoRefs.light(plain) === plain)
    }

    @Test fun decodingLightReadsNoPhoto() {
        val full = person(3, photoSeed = 3)
        var asked = 0
        val line = RecordJson.encode(full)
        val light = RecordJson.decodeLight(line)
        assertTrue(light.blobs().isEmpty())
        assertEquals(PhotoRefs.light(full), light)
        assertEquals(full, PhotoRefs.filled(light) { asked++; bytes(3) })
        assertEquals(1, asked)
    }

    @Test fun aMissingPhotoIsDroppedAndTheContactKept() {
        val light = PhotoRefs.light(person(4, photoSeed = 4))
        // Planning finds it first and says how many went.
        val (planned, dropped) = PhotoRefs.resolvable(light) { false }
        assertEquals(1, dropped)
        assertTrue(planned.raws.single().rows.none { it.mimeType == Mime.PHOTO })
        assertEquals(1, planned.raws.single().rows.count { it.mimeType == Mime.PHONE })
        // Writing never stops on one either: the contact goes without its photo.
        val written = PhotoRefs.filled(light) { null }
        assertTrue(written.raws.single().rows.none { it.mimeType == Mime.PHOTO })
        // A resolvable photo is kept as it is.
        val (kept, none) = PhotoRefs.resolvable(light) { true }
        assertEquals(0, none)
        assertTrue(kept === light)
    }

    /**
     * A Replace restore whose archive lacks one contact's photo: planning finds it before anything is deleted, and
     * every contact is still written (that one without its photo), instead of the restore stopping part-way.
     */
    @Test fun aReplacePlanKnowsAboutAMissingPhotoBeforeDeletingAnything() {
        val backup = (1..3).map { i -> PhotoRefs.light(person(i, photoSeed = i)) }
        val held = listOf(1, 3).associate { RecordJson.sha256Hex(bytes(it)) to bytes(it) }
        var missing = 0
        val planned = backup.map { r -> PhotoRefs.resolvable(r, held::containsKey).let { (kept, n) -> missing += n; kept } }
        val plan = MergePlanner.plan(listOf(person(9, photoSeed = null)), planned, RestoreMode.REPLACE).copy(missingPhotos = missing)
        assertEquals(1, plan.missingPhotos)
        assertEquals(1, plan.toDelete.size)
        val written = plan.actions.filterIsInstance<MergeAction.New>().map { PhotoRefs.filled(it.backup, held::get) }
        assertEquals(3, written.size)
        assertEquals(listOf(1, 0, 1), written.map { r -> r.blobs().size })
    }

    /**
     * 20,000 contacts on each side, a third with photos: the plan holds no photo bytes at all, matches the same photo by
     * hash, flags a changed photo, and the new contacts get their photos back when written.
     */
    @Test fun twentyThousandContactsArePlannedWithoutTheirPhotos() {
        val n = 20_000
        val existing = (0 until n).filter { it % 7 != 0 }.map { i -> PhotoRefs.light(person(i, photoSeed = if (i % 3 == 0) i else null)) }
        val photos = HashMap<String, ByteArray>()
        val lines = (0 until n).map { i ->
            val seed = when {
                i % 3 != 0 -> null
                i % 10 == 0 -> i + 1 // a different photo in the backup
                else -> i
            }
            RecordJson.encode(person(i, seed)) { h, b -> photos[h] = b }
        }
        val t = System.nanoTime()
        val backup = lines.asSequence().map(RecordJson::decodeLight).toList()
        val plan = MergePlanner.plan(existing, backup)
        val ms = (System.nanoTime() - t) / 1_000_000
        // What planning with full records held: every photo of both books (here 4 KiB each; on a phone 10-300 KiB).
        val heldBefore = (existing.size + backup.size) / 3 * 4_096L
        println("Restore plan, $n contacts a side: $ms ms; photo bytes held while planning: 0 (before: ${heldBefore shr 20} MiB at 4 KiB a photo)")

        assertTrue(plan.actions.all { it.backup.blobs().isEmpty() })
        val news = plan.actions.filterIsInstance<MergeAction.New>()
        assertEquals((0 until n).count { it % 7 == 0 }, news.size)
        val conflicts = plan.actions.filterIsInstance<MergeAction.Conflict>()
        assertEquals((0 until n).count { it % 7 != 0 && it % 30 == 0 }, conflicts.size)
        assertTrue(conflicts.all { "Different photo" in it.reasons })
        // Every other match is identical: same photo by hash, or no photo.
        assertEquals(n - news.size - conflicts.size, plan.summary.identical)

        val written = news.map { PhotoRefs.filled(it.backup, photos::get) }
        val withPhoto = written.filter { it.key.removePrefix("k").toInt() % 3 == 0 }
        assertTrue(withPhoto.isNotEmpty())
        for (r in withPhoto) {
            val i = r.key.removePrefix("k").toInt()
            assertArrayEquals(bytes(if (i % 10 == 0) i + 1 else i), r.blobs().single())
            assertNull(r.raws.single().rows.single { it.mimeType == Mime.PHOTO }.values[PhotoRefs.HASH])
        }
    }
}
