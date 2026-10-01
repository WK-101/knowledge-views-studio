package app.parley.ui.people

import app.parley.common.people.MeCard
import app.parley.common.people.Profile
import app.parley.common.people.ProfileService
import app.parley.common.people.SocialProfiles
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** My card through the contact editor and back: no website row is ever lost. */
class MeCardDetailsTest {
    @Test fun a_labelled_link_that_is_not_a_profile_stays_a_website() {
        val article = "https://www.linkedin.com/pulse/how-we-work-ana-lima"
        val links = "https://linktr.ee/analima"
        val d = ContactDetails(
            given = "Ana",
            websites = listOf(
                DataItem(value = "https://www.linkedin.com/in/ana-lima-123", type = SocialProfiles.TYPE_CUSTOM, label = "LinkedIn"),
                DataItem(value = article, type = SocialProfiles.TYPE_CUSTOM, label = "LinkedIn"),
                DataItem(value = links, type = SocialProfiles.TYPE_CUSTOM, label = "Instagram"),
                DataItem(value = "https://ana.example", type = 1),
            ),
        )
        val card = MeCardDetails.toCard(d)
        assertEquals(listOf(Profile(ProfileService.LINKEDIN, "ana-lima-123")), card.profiles)
        assertEquals(listOf(article, links, "https://ana.example"), card.websites)
        // And again: saving twice changes nothing.
        assertEquals(card, MeCardDetails.toCard(MeCardDetails.toDetails(card)))
    }

    @Test fun a_card_round_trips() {
        val card = MeCard(name = "Ana Lima", websites = listOf("https://ana.example"), profiles = listOf(Profile(ProfileService.GITHUB, "analima")))
        assertEquals(card, MeCardDetails.toCard(MeCardDetails.toDetails(card)))
    }
}
