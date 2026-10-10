package app.parley.data.people

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.people.MeCards
import app.parley.common.people.Profile
import app.parley.common.people.ProfileService
import app.parley.common.people.RelationLinks
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** My card kept as a whole contact: a card kept in its short form is read into it once, losing nothing. */
@RunWith(RobolectricTestRunner::class)
class MeCardStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("me_card", Context.MODE_PRIVATE)

    @Before fun setUp() {
        prefs.edit().clear().commit()
    }

    /** The short form as it was stored before My card held every field. */
    private fun oldCard(): String = JSONObject()
        .put("name", "Anna Maria Smith").put("phones", JSONArray(listOf("+44 7700 900123", "+44 20 7946 0500")))
        .put("emails", JSONArray(listOf("anna@example.org"))).put("company", "Acme").put("title", "Engineer")
        .put("sites", JSONArray(listOf("https://anna.example"))).put("address", "1 High St, London").put("note", "Gate code 12")
        .put("profiles", JSONArray().put(JSONObject().put("s", ProfileService.GITHUB.key).put("h", "annas")))
        .toString()

    @Test fun a_card_kept_in_its_short_form_is_migrated_without_loss() {
        prefs.edit().putString("card", oldCard()).putString("share_parts", "NAME,PHONES").putBoolean("migrated_my_details", true).commit()
        val store = MeCardStore(context)
        val card = store.card.value
        assertEquals("Anna Maria Smith", card.name)
        assertEquals(listOf("+44 7700 900123", "+44 20 7946 0500"), card.phones)
        assertEquals(listOf("anna@example.org"), card.emails)
        assertEquals("Acme" to "Engineer", card.company to card.title)
        assertEquals(listOf("https://anna.example"), card.websites)
        assertEquals(listOf(Profile(ProfileService.GITHUB, "annas")), card.profiles)
        assertEquals("1 High St, London", card.address)
        assertEquals("Gate code 12", card.note)
        // As a whole contact: first and last name, the address in its street line.
        val d = store.details.value
        assertEquals("Anna Maria" to "Smith", d.given to d.family)
        assertEquals("1 High St, London", d.addresses.single().street)
        // Stored the new way once, the old copy gone; the choice of what to share is kept as it was.
        assertNull(prefs.getString("card", null))
        assertTrue(prefs.getString("details", null) != null)
        assertEquals(setOf(MeCards.Part.NAME, MeCards.Part.PHONES), store.shareParts.value)
        assertEquals(card, MeCardStore(context).card.value)
    }

    @Test fun the_whole_card_its_links_and_the_backup_round_trip() {
        val store = MeCardStore(context)
        val d = ContactDetails(
            given = "Ana", nickname = "Annie", pronouns = "she/her",
            phones = listOf(DataItem(value = "+447700900123", type = 3, label = null), DataItem(value = "+441234", type = 0, label = "Boat")),
            relations = listOf(DataItem(value = "Sam", type = 0, label = "Husband")),
        )
        val links = mapOf("sam" to RelationLinks.Link("sam-key", 7))
        store.save(d, links)
        val json = store.exportJson()!!
        prefs.edit().clear().commit()
        val fresh = MeCardStore(context)
        assertTrue(MeCardDetails.isEmpty(fresh.details.value))
        fresh.importJson(json)
        assertEquals(d, fresh.details.value.copy(displayName = ""))
        assertEquals(links, fresh.links.value)
        // A contact's new key carries the link along; a deleted contact's link goes, the name stays.
        fresh.rekeyLinks("sam-key", "sam-key-2", 8)
        assertEquals(RelationLinks.Link("sam-key-2", 8), fresh.links.value["sam"])
        fresh.forgetLinks("sam-key-2")
        assertTrue(fresh.links.value.isEmpty())
        assertEquals("Sam", fresh.details.value.relations.single().value)
    }

    @Test fun a_backup_from_before_my_card_held_every_field_still_restores() {
        val store = MeCardStore(context)
        store.importJson(oldCard())
        assertEquals("Anna Maria Smith", store.card.value.name)
        assertFalse(store.card.value.isEmpty)
        // A filled-in card is never overwritten by a restore.
        store.importJson(JSONObject().put("name", "Someone Else").toString())
        assertEquals("Anna Maria Smith", store.card.value.name)
    }

    @Test fun a_restore_never_overwrites_a_card_holding_only_the_new_kinds_of_field() {
        val store = MeCardStore(context)
        store.save(ContactDetails(events = listOf(app.parley.data.EventItem(date = "1990-04-01")), pronouns = "she/her"))
        store.importJson(oldCard())
        assertEquals("1990-04-01", store.details.value.events.single().date)
        assertEquals("she/her", store.details.value.pronouns)
        assertTrue("the backup's card didn't replace it", store.card.value.name.isEmpty())
    }
}
