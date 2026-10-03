package app.parley.common.record

import app.parley.common.record.NewContactAccount.Decision
import app.parley.common.record.NewContactAccount.State
import app.parley.common.record.NewContactAccount.SystemDefault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewContactAccountTest {
    private val phone = "phone"
    private val google = "google:ana"
    private val work = "dav:work"
    private val sim = "sim"
    private val isLocal: (String) -> Boolean = { it == phone || it == sim }
    private val cloud = SystemDefault(State.CLOUD, google)

    private fun decide(sdk: Int, default: SystemDefault<String>?, requested: String?) =
        NewContactAccount.decide(sdk, default, requested, phone, isLocal)

    @Test fun platform_states_map() {
        assertEquals(State.NOT_SET, NewContactAccount.state(1))
        assertEquals(State.LOCAL, NewContactAccount.state(2))
        assertEquals(State.CLOUD, NewContactAccount.state(3))
        assertEquals(State.SIM, NewContactAccount.state(4))
        assertEquals(State.UNKNOWN, NewContactAccount.state(-1))
    }

    @Test fun before_android_16_nothing_changes() {
        assertEquals(Decision(phone, false), decide(35, cloud, null))
        assertEquals(Decision(phone, false), decide(35, cloud, phone))
        assertNull(NewContactAccount.cloudInstead(35, cloud))
    }

    @Test fun unchosen_follows_the_cloud_default_quietly() {
        assertEquals(Decision(google, false), decide(36, cloud, null))
    }

    @Test fun chosen_phone_goes_to_the_cloud_default_and_says_so() {
        assertEquals(Decision(google, true), decide(36, cloud, phone))
        assertEquals(Decision(google, true), decide(37, cloud, sim))
    }

    @Test fun a_chosen_cloud_account_is_kept() {
        assertEquals(Decision(work, false), decide(36, cloud, work))
        assertEquals(Decision(google, false), decide(36, cloud, google))
    }

    @Test fun phone_sim_unset_or_unreadable_defaults_keep_the_phone() {
        val defaults: List<SystemDefault<String>?> = listOf(SystemDefault(State.LOCAL, null), SystemDefault(State.NOT_SET, null), SystemDefault(State.SIM, sim), SystemDefault(State.UNKNOWN, null), null)
        for (d in defaults) {
            assertEquals(Decision(phone, false), decide(36, d, null))
            assertEquals(Decision(phone, false), decide(36, d, phone))
        }
    }

    @Test fun cloud_state_without_an_account_changes_nothing() {
        assertEquals(Decision(phone, false), decide(36, SystemDefault(State.CLOUD, null), phone))
    }

    @Test fun pickers_offer_the_default_first_and_no_phone() {
        val targets = listOf(phone, work, google)
        assertEquals(listOf(google, work), NewContactAccount.offered(targets, 36, cloud, isLocal))
        // The default is offered even when the target list didn't know it.
        assertEquals(listOf(google, work), NewContactAccount.offered(listOf(phone, work), 36, cloud, isLocal))
        assertEquals(targets, NewContactAccount.offered(targets, 35, cloud, isLocal))
        assertEquals(targets, NewContactAccount.offered(targets, 36, SystemDefault(State.LOCAL, null), isLocal))
    }
}
