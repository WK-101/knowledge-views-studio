package app.parley.common.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLedgerTest {
    private fun r(id: String, name: String, number: String?, at: Long, phones: List<String> = listOf("+447700900123"), m: ShareMethod = ShareMethod.QR_SWAP) =
        ShareReceipt(id, name, number, m, at, phones)

    @Test fun receipts_group_by_person_newest_first() {
        val list = listOf(
            r("1", "Bo", "+447700900001", 10),
            r("2", "", "07700 900001", 20, m = ShareMethod.SEND_DETAILS),
            r("3", "Cy", null, 15),
            r("4", "cy", null, 5),
        )
        val people = ShareLedger.people(list, "GB")
        assertEquals(2, people.size)
        assertEquals("2", people[0].latest.id)
        assertEquals("Bo", people[0].name)
        assertEquals(2, people[1].receipts.size)
    }

    @Test fun round_trip_and_cap() {
        var list = emptyList<ShareReceipt>()
        repeat(ShareLedger.MAX + 3) { list = ShareLedger.add(list, r("$it", "P$it", "+4477009${it.toString().padStart(5, '0')}", it.toLong())) }
        assertEquals(ShareLedger.MAX, list.size)
        assertEquals("${ShareLedger.MAX + 2}", list.first().id)
        assertEquals(list, ShareLedger.decode(ShareLedger.encode(list)))
        assertTrue(ShareLedger.decode(null).isEmpty())
    }

    @Test fun who_has_an_old_number() {
        val list = listOf(
            r("1", "Bo", "+447700900001", 10),
            r("2", "Cy", "+447700900002", 20, phones = listOf("+447700900456")),
            r("3", "Di", null, 30),
            r("4", "Ed", "+447700900004", 40, phones = emptyList()),
        )
        val out = ShareLedger.outdated(list, listOf("+44 7700 900456"), "GB")
        assertEquals(listOf("Bo"), out.map { it.name })
        // Telling Bo the new number makes Bo current.
        val told = ShareLedger.add(list, r("5", "Bo", "+447700900001", 50, phones = listOf("+447700900456"), m = ShareMethod.NEW_NUMBER))
        assertTrue(ShareLedger.outdated(told, listOf("+447700900456"), "GB").isEmpty())
        assertTrue(ShareLedger.outdated(list, emptyList(), "GB").isEmpty())
        assertEquals(ShareLedger.numbersKey(listOf("+447700900456")), ShareLedger.numbersKey(listOf("07700 900456")))
    }
}
