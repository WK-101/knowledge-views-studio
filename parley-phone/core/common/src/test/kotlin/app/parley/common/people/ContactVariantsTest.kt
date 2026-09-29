package app.parley.common.people

import app.parley.common.storage.ContactKeyedStores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactVariantsTest {
    @Test fun nav_ids_round_trip_for_both_storages() {
        assertEquals(ContactRef.Device(42), ContactRef.ofNavId(42))
        assertEquals(ContactRef.Private(7), ContactRef.ofNavId(-7))
        assertNull(ContactRef.ofNavId(0))
        assertEquals(-7L, ContactRef.Private(7).navId)
        assertEquals(42L, ContactRef.Device(42).navId)
    }

    @Test fun private_keys_never_look_like_lookup_keys_and_parse_back() {
        val key = ContactRef.privateKey(12)
        assertTrue(ContactRef.isPrivateKey(key))
        assertEquals(12L, ContactRef.vaultIdOf(key))
        // Real lookup keys (local, Google, linked) are never private keys.
        listOf("0r1-2A4E", "3789r12-4F2B.1234i5", "lk5", "").forEach {
            assertFalse(it, ContactRef.isPrivateKey(it))
            assertNull(ContactRef.vaultIdOf(it))
        }
        assertNull(ContactRef.vaultIdOf("parley-private:x"))
        assertNull(ContactRef.vaultIdOf("parley-private:0"))
    }

    @Test fun the_parley_key_is_the_lookup_key_for_device_and_the_private_key_otherwise() {
        assertEquals("0r1-AB", ContactRef.Device(1).parleyKey("0r1-AB"))
        assertEquals("parley-private:3", ContactRef.Private(3).parleyKey("ignored"))
    }

    @Test fun chips_show_only_the_variants_a_contact_has() {
        assertEquals(emptyList<VariantChip>(), ContactVariants(ContactStorage.DEVICE).chips)
        assertEquals(listOf(VariantChip.Private), ContactVariants(ContactStorage.PRIVATE).chips)
        assertEquals(listOf(VariantChip.Temporary(5)), ContactVariants(ContactStorage.DEVICE, 5).chips)
        assertEquals(listOf(VariantChip.Private, VariantChip.Temporary(5)), ContactVariants(ContactStorage.PRIVATE, 5).chips)
    }

    @Test fun every_variant_converts_both_ways() {
        assertEquals(
            listOf(ContactConversion.MAKE_PRIVATE, ContactConversion.MAKE_TEMPORARY),
            ContactVariants(ContactStorage.DEVICE).conversions,
        )
        assertEquals(
            listOf(ContactConversion.MAKE_VISIBLE, ContactConversion.KEEP_PERMANENTLY, ContactConversion.CHANGE_EXPIRY),
            ContactVariants(ContactStorage.PRIVATE, 99).conversions,
        )
    }

    @Test fun private_contacts_have_everything_but_the_address_books_own_features() {
        val device = ContactCapabilities.of(ContactStorage.DEVICE)
        val private = ContactCapabilities.of(ContactStorage.PRIVATE)
        assertEquals(ContactCapability.entries.toSet(), device)
        assertTrue(device.containsAll(private))
        // Each difference says why, so the page and docs/CONTACT_MODEL.md can explain it.
        (device - private).forEach { assertNotNull(it.name, it.deviceOnlyReason) }
        // The page's main features are the same for both.
        listOf(
            ContactCapability.REACH_VIA_APPS, ContactCapability.TIMELINE, ContactCapability.CALL_INSIGHTS, ContactCapability.CIRCLE,
            ContactCapability.CALL_SCREEN_PICTURE, ContactCapability.FAVOURITE, ContactCapability.MAP_LINKS, ContactCapability.RELATIONS,
            ContactCapability.QR_CODE, ContactCapability.TEMPORARY, ContactCapability.NOTE_FOR_CALLS,
        ).forEach { assertTrue(it.name, ContactCapabilities.has(ContactStorage.PRIVATE, it)) }
        assertFalse(ContactCapabilities.has(ContactStorage.PRIVATE, ContactCapability.HOME_SCREEN_SHORTCUT))
        assertFalse(ContactCapabilities.has(ContactStorage.PRIVATE, ContactCapability.SHARE_VCARD_FILE))
    }

    @Test fun every_contact_keyed_store_is_in_the_registry() {
        ContactKeyedStores.all.zip(ContactKeyedStores.resolved()).forEach { (entry, store) -> assertNotNull(entry.second, store) }
    }
}
