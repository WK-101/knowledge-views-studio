package app.parley.data.people

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.cards.CardArrival
import app.parley.common.cards.CardCheck
import app.parley.common.cards.CardLinks
import app.parley.common.cards.ShareMethod
import app.parley.common.cards.SignedCards
import app.parley.common.people.MeCard
import app.parley.common.people.MeCards
import app.parley.common.storage.ContactKeyedStores
import app.parley.common.storage.StoreKind
import app.parley.data.security.RecordCrypto
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** My card's signing identity, "Shared with" and the card links: sealed at rest, versions that only grow, keys that follow. */
@RunWith(RobolectricTestRunner::class)
class CardStoresTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ana = MeCard(name = "Ana Pérez", phones = listOf("+447700900123"), emails = listOf("ana@example.org"))

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        listOf("my_card_identity", "card_sharing", "card_links").forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun signedVcard(id: MyCardIdentity, card: MeCard, parts: Set<MeCards.Part> = MeCards.defaultParts) =
        SignedCards.attach(MeCards.vcard(card, parts), id.sign(card, parts)!!)

    @Test fun the_version_grows_only_when_the_card_changes() {
        val id = MyCardIdentity(context)
        val v1 = (SignedCards.check(signedVcard(id, ana)).single() as CardCheck.Signed).card
        assertEquals(1L, v1.version)
        // Sharing again, or sharing fewer parts, is the same version.
        assertEquals(1L, id.sign(ana, setOf(MeCards.Part.NAME))!!.version)
        val v2 = (SignedCards.check(signedVcard(id, ana.copy(phones = listOf("+447700900456")))).single() as CardCheck.Signed).card
        assertEquals(2L, v2.version)
        assertEquals(v1.cardId, v2.cardId)
        assertEquals(v1.publicKey, v2.publicKey)
        assertEquals(CardArrival.UPDATE, CardLinks.arrival(CardLinks.first(v1, 0), v2))
        // The secret is never stored in plain text.
        val stored = context.getSharedPreferences("my_card_identity", Context.MODE_PRIVATE).getString("secret", null)
        assertTrue(RecordCrypto.get(context).isSealed(stored))
    }

    @Test fun a_backup_moves_the_identity_to_a_phone_that_never_signed() {
        val old = MyCardIdentity(context)
        old.sign(ana, MeCards.defaultParts)
        val json = old.exportJson()!!
        val oldKey = old.sign(ana, MeCards.defaultParts)!!.publicKey
        context.getSharedPreferences("my_card_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val fresh = MyCardIdentity(context)
        fresh.importJson(json)
        assertEquals(oldKey, fresh.sign(ana, MeCards.defaultParts)!!.publicKey)
        assertEquals(1L, fresh.version)
        // A phone that already signed its own card keeps it.
        val other = MyCardIdentity(context)
        val mine = other.sign(ana.copy(name = "Other"), MeCards.defaultParts)!!.publicKey
        other.importJson("""{"id":"x","key":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","v":9}""")
        assertEquals(mine, other.sign(ana, MeCards.defaultParts)!!.publicKey)
    }

    @Test fun the_ledger_is_sealed_and_survives_a_restart() = runBlocking {
        val store = ShareLedgerStore(context)
        assertTrue(store.record("Bo", "+447700900001", ShareMethod.QR_SWAP, listOf("+447700900123")))
        val raw = context.getSharedPreferences("card_sharing", Context.MODE_PRIVATE).getString("ledger", null)
        assertTrue(RecordCrypto.get(context).isSealed(raw))
        assertFalse(raw!!.contains("Bo"))
        val again = ShareLedgerStore(context)
        assertTrue(again.load())
        assertEquals("Bo", again.receipts.value.single().name)
        again.remove(setOf(again.receipts.value.single().id))
        assertTrue(again.receipts.value.isEmpty())
        // A backup's receipts merge in.
        store.importJson(store.exportJson() ?: "[]")
    }

    @Test fun links_follow_their_contact_and_hold_cards_for_new_ones() = runBlocking {
        val id = MyCardIdentity(context)
        val card = (SignedCards.check(signedVcard(id, ana)).single() as CardCheck.Signed).card
        val store = CardLinkStore(context)
        store.update { it.hold(card, System.currentTimeMillis()) }
        store.update { b -> b.linkHeld("lookup-1", System.currentTimeMillis()) { f -> "+447700900123" in f.phones } ?: b }
        assertNotNull(store.book.value.links["lookup-1"])
        assertTrue(store.book.value.held.isEmpty())
        store.rekey("lookup-1", "parley-private:7")
        assertNull(store.book.value.links["lookup-1"])
        assertEquals(card.cardId, CardLinkStore(context).let { it.load(); it.book.value.links["parley-private:7"]?.cardId })
        store.forget("parley-private:7")
        assertTrue(store.book.value.links.isEmpty())
        assertTrue(ContactKeyedStores.all.contains(StoreKind.PREFS to "card_links"))
    }
}
