package app.parley.data.people

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.cards.CardArrival
import app.parley.common.cards.CardCheck
import app.parley.common.cards.CardFields
import app.parley.common.cards.CardLinkBook
import app.parley.common.cards.CardLinks
import app.parley.common.cards.ShareMethod
import app.parley.common.cards.SignedCards
import app.parley.common.people.MeCard
import app.parley.common.people.ContactRef
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
        assertTrue(v1.version > 0)
        // Sharing again, or sharing fewer parts, is the same version (M4); the parts are signed with it (M3).
        val narrow = id.sign(ana, setOf(MeCards.Part.NAME))!!
        assertEquals(v1.version, narrow.version)
        assertEquals(setOf(MeCards.Part.NAME), narrow.parts)
        val v2 = (SignedCards.check(signedVcard(id, ana.copy(phones = listOf("+447700900456")))).single() as CardCheck.Signed).card
        assertTrue(v2.version > v1.version)
        assertEquals(v1.cardId, v2.cardId)
        assertEquals(v1.publicKey, v2.publicKey)
        assertEquals(CardArrival.UPDATE, CardLinks.arrival(CardLinks.first(v1, 0), v2))
        // The secret is never stored in plain text.
        val stored = context.getSharedPreferences("my_card_identity", Context.MODE_PRIVATE).getString("secret", null)
        assertTrue(RecordCrypto.get(context).isSealed(stored))
    }

    @Test fun a_backup_moves_the_identity_to_a_phone_whose_card_never_left_it() {
        val old = MyCardIdentity(context)
        old.sign(ana, MeCards.defaultParts)
        old.markShared()
        val json = old.exportJson()!!
        val oldKey = old.sign(ana, MeCards.defaultParts)!!.publicKey
        val oldVersion = old.version
        context.getSharedPreferences("my_card_identity", Context.MODE_PRIVATE).edit().clear().commit()
        // M5: showing the QR code (signing) on the new phone doesn't make its own key "used".
        val fresh = MyCardIdentity(context)
        val shown = fresh.sign(ana.copy(name = "Shown"), MeCards.defaultParts)!!.publicKey
        fresh.importJson(json)
        assertFalse(fresh.restoreChoice.value)
        val after = fresh.sign(ana, MeCards.defaultParts)!!
        assertEquals(oldKey, after.publicKey)
        assertTrue(shown != after.publicKey)
        // M6: versions never go back across a restore.
        assertTrue(after.version >= oldVersion)
    }

    @Test fun a_phone_whose_card_was_shared_asks_which_key_to_keep() {
        val backup = MyCardIdentity(context).let { it.sign(ana, MeCards.defaultParts); it.markShared(); it.exportJson()!! }
        val backupKey = MyCardIdentity(context).sign(ana, MeCards.defaultParts)!!.publicKey
        context.getSharedPreferences("my_card_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val phone = MyCardIdentity(context)
        val mine = phone.sign(ana.copy(name = "Other"), MeCards.defaultParts)!!.publicKey
        phone.markShared()
        phone.importJson(backup)
        // Not skipped silently: kept until the user chooses.
        assertTrue(phone.restoreChoice.value)
        assertEquals(mine, phone.sign(ana, MeCards.defaultParts)!!.publicKey)
        assertTrue(MyCardIdentity(context).restoreChoice.value)
        assertTrue(phone.usePrevious())
        assertFalse(phone.restoreChoice.value)
        assertEquals(backupKey, phone.sign(ana, MeCards.defaultParts)!!.publicKey)
        // "Keep this phone's key" drops the backup's.
        phone.importJson("""{"id":"x","key":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","v":9}""")
        assertTrue(phone.restoreChoice.value)
        phone.keepThis()
        assertFalse(phone.restoreChoice.value)
        assertEquals(backupKey, phone.sign(ana, MeCards.defaultParts)!!.publicKey)
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

    @Test fun private_contacts_receipts_keep_no_name_and_stay_out_of_the_general_backup() = runBlocking {
        val store = ShareLedgerStore(context)
        store.record("Bo", "+447700900001", ShareMethod.QR_SWAP, listOf("+447700900123"))
        store.record("Secret Sam", "+447700900002", ShareMethod.INTRODUCE, listOf("+447700900123"), contactKey = "parley-private:7")
        val sam = store.receipts.value.first { it.contactKey != null }
        assertEquals("", sam.name)
        assertNull(sam.number)
        // Sealed with the vault key in a document of its own; the general backup never has it.
        val prefs = context.getSharedPreferences("card_sharing", Context.MODE_PRIVATE)
        assertNotNull(prefs.getString("ledger_private", null))
        assertFalse(store.exportJson()!!.contains("parley-private"))
        val own = store.exportFor("parley-private:7")!!
        val again = ShareLedgerStore(context)
        assertTrue(again.load())
        assertEquals(2, again.receipts.value.size)
        // Restored under the contact's new key.
        context.getSharedPreferences("card_sharing", Context.MODE_PRIVATE).edit().clear().commit()
        val restored = ShareLedgerStore(context)
        restored.importJson(store.exportJson()!!)
        restored.importFor("parley-private:9", own)
        assertEquals(listOf(null, "parley-private:9"), restored.receipts.value.map { it.contactKey }.sortedBy { it.orEmpty() })
    }

    @Test fun private_card_links_are_sealed_with_the_vault_key() = runBlocking {
        val card = (SignedCards.check(signedVcard(MyCardIdentity(context), ana)).single() as CardCheck.Signed).card
        val store = CardLinkStore(context)
        store.update { it.link("parley-private:7", card, 1) }
        val prefs = context.getSharedPreferences("card_links", Context.MODE_PRIVATE)
        assertNotNull(prefs.getString("book_private", null))
        assertFalse(RecordCrypto.get(context).openText(prefs.getString("book", null)).orEmpty().contains("parley-private"))
        assertEquals(card.cardId, CardLinkStore(context).let { it.load(); it.book.value.links["parley-private:7"]?.cardId })
        // Not in the device section of a backup.
        assertNull(store.exportDevice(ContactRef::isPrivateKey))
    }

    @Test fun a_restored_link_never_replaces_one_the_contact_has() = runBlocking {
        val id = MyCardIdentity(context)
        val mine = (SignedCards.check(signedVcard(id, ana)).single() as CardCheck.Signed).card
        val store = CardLinkStore(context)
        store.update { it.link("lookup-1", mine, 1) }
        val other = SignedCards.sign(CardFields(name = "Ana", phones = listOf("+447700900123")), "other-card", 1, ByteArray(32) { 5 })
        store.importOne("lookup-1", CardLinkBook.encode(CardLinkBook().link("x", other, 1)))
        assertEquals(mine.cardId, store.book.value.links["lookup-1"]?.cardId)
    }

    @Test fun the_new_stores_are_resealed_by_the_sweep() = runBlocking {
        val prefs = context.getSharedPreferences("card_links", Context.MODE_PRIVATE)
        prefs.edit().putString("book", CardLinkBook.encode(CardLinkBook())).commit()
        val store = CardLinkStore(context)
        assertTrue(store.resealPlain())
        assertTrue(RecordCrypto.get(context).isSealed(prefs.getString("book", null)))
        val ledger = context.getSharedPreferences("card_sharing", Context.MODE_PRIVATE)
        ledger.edit().putString("ledger", "[]").commit()
        assertTrue(ShareLedgerStore(context).resealPlain())
        assertTrue(RecordCrypto.get(context).isSealed(ledger.getString("ledger", null)))
        assertTrue(MyCardIdentity(context).resealPlain())
    }

    @Test fun links_follow_their_contact_and_held_cards_wait_for_the_users_choice() = runBlocking {
        val id = MyCardIdentity(context)
        val card = (SignedCards.check(signedVcard(id, ana)).single() as CardCheck.Signed).card
        val store = CardLinkStore(context)
        store.update { it.hold(card, System.currentTimeMillis()) }
        assertTrue(store.book.value.links.isEmpty())
        store.update { b -> b.linkHeld("lookup-1", card.cardId, card.publicKey, System.currentTimeMillis()) ?: b }
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
