package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SocialProfilesTest {
    private fun assertUrl(url: String, service: ProfileService, handle: String) {
        val p = SocialProfiles.fromUrl(url)
        assertNotNull("$url is a $service profile", p)
        assertEquals(url, service, p!!.service)
        assertEquals(url, handle, p.handle)
    }

    @Test fun real_profile_addresses_give_their_service_and_handle() {
        assertUrl("https://www.instagram.com/natgeo/", ProfileService.INSTAGRAM, "natgeo")
        assertUrl("https://instagram.com/ana.lima?igsh=MWZ1bmQ5eXg2a3BhaQ==", ProfileService.INSTAGRAM, "ana.lima")
        assertUrl("instagram.com/ana_lima", ProfileService.INSTAGRAM, "ana_lima")
        assertUrl("https://www.instagram.com/stories/natgeo/3456789012345678901/", ProfileService.INSTAGRAM, "natgeo")
        assertUrl("https://www.linkedin.com/in/williamhgates/", ProfileService.LINKEDIN, "williamhgates")
        assertUrl("https://uk.linkedin.com/in/ana-lima-0b1234567", ProfileService.LINKEDIN, "ana-lima-0b1234567")
        assertUrl("https://www.linkedin.com/company/microsoft/", ProfileService.LINKEDIN, "company/microsoft")
        assertUrl("https://x.com/NASA", ProfileService.X, "NASA")
        assertUrl("https://twitter.com/jack?lang=en", ProfileService.X, "jack")
        assertUrl("https://mobile.twitter.com/ana_lima/status/1234567890", ProfileService.X, "ana_lima")
        assertUrl("https://www.facebook.com/zuck", ProfileService.FACEBOOK, "zuck")
        assertUrl("https://m.facebook.com/profile.php?id=100012345678901", ProfileService.FACEBOOK, "100012345678901")
        assertUrl("https://www.facebook.com/people/Ana-Lima/100012345678901/", ProfileService.FACEBOOK, "100012345678901")
        assertUrl("https://www.tiktok.com/@khaby.lame", ProfileService.TIKTOK, "khaby.lame")
        assertUrl("https://www.tiktok.com/@khaby.lame/video/7300000000000000000", ProfileService.TIKTOK, "khaby.lame")
        assertUrl("https://www.youtube.com/@MrBeast", ProfileService.YOUTUBE, "@MrBeast")
        assertUrl("https://m.youtube.com/@MrBeast/videos", ProfileService.YOUTUBE, "@MrBeast")
        assertUrl("https://www.youtube.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA", ProfileService.YOUTUBE, "channel/UCX6OQ3DkcsbYNE6H8uQQuVA")
        assertUrl("https://www.youtube.com/user/PewDiePie", ProfileService.YOUTUBE, "user/PewDiePie")
        assertUrl("https://www.snapchat.com/add/djkhaled305", ProfileService.SNAPCHAT, "djkhaled305")
        assertUrl("https://www.snapchat.com/@djkhaled305", ProfileService.SNAPCHAT, "djkhaled305")
        assertUrl("https://www.threads.net/@zuck", ProfileService.THREADS, "zuck")
        assertUrl("https://www.threads.com/@ana.lima", ProfileService.THREADS, "ana.lima")
        assertUrl("https://bsky.app/profile/jay.bsky.team", ProfileService.BLUESKY, "jay.bsky.team")
        assertUrl("https://bsky.app/profile/did:plc:z72i7hdynmk6r22z27h6tvur", ProfileService.BLUESKY, "did:plc:z72i7hdynmk6r22z27h6tvur")
        assertUrl("https://mastodon.social/@Gargron", ProfileService.MASTODON, "Gargron@mastodon.social")
        assertUrl("https://fosstodon.org/users/ana", ProfileService.MASTODON, "ana@fosstodon.org")
        assertUrl("https://github.com/torvalds", ProfileService.GITHUB, "torvalds")
        assertUrl("https://github.com/orgs/android/repositories", ProfileService.GITHUB, "android")
        assertUrl("https://github.com/torvalds/linux", ProfileService.GITHUB, "torvalds")
        assertUrl("https://www.reddit.com/user/spez/", ProfileService.REDDIT, "spez")
        assertUrl("https://old.reddit.com/u/spez", ProfileService.REDDIT, "spez")
        assertUrl("https://www.pinterest.com/marthastewart/", ProfileService.PINTEREST, "marthastewart")
        assertUrl("https://www.pinterest.co.uk/marthastewart/", ProfileService.PINTEREST, "marthastewart")
        assertUrl("https://de.pinterest.com/marthastewart/", ProfileService.PINTEREST, "marthastewart")
        assertUrl("https://www.twitch.tv/shroud", ProfileService.TWITCH, "shroud")
        assertUrl("https://www.behance.net/analima", ProfileService.BEHANCE, "analima")
        assertUrl("https://dribbble.com/analima", ProfileService.DRIBBBLE, "analima")
    }

    @Test fun pages_that_are_not_people_are_ordinary_websites() {
        assertNull(SocialProfiles.fromUrl("https://www.instagram.com/p/C1a2B3c4D5e/"))
        assertNull(SocialProfiles.fromUrl("https://www.instagram.com/"))
        assertNull(SocialProfiles.fromUrl("https://x.com/home"))
        assertNull(SocialProfiles.fromUrl("https://x.com/search?q=parley"))
        assertNull(SocialProfiles.fromUrl("https://www.facebook.com/groups/123456"))
        assertNull(SocialProfiles.fromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(SocialProfiles.fromUrl("https://www.reddit.com/r/android/"))
        assertNull(SocialProfiles.fromUrl("https://github.com/features/actions"))
        assertNull(SocialProfiles.fromUrl("https://www.linkedin.com/feed/"))
        assertNull(SocialProfiles.fromUrl("https://www.tiktok.com/foryou"))
        assertNull(SocialProfiles.fromUrl("https://example.com/@ana"))
        assertNull(SocialProfiles.fromUrl("https://ana.example.com/"))
        assertNull(SocialProfiles.fromUrl("mailto:ana@example.com"))
        assertNull(SocialProfiles.fromUrl("not a link"))
        assertNull(SocialProfiles.fromUrl(""))
    }

    @Test fun typed_or_pasted_values_become_the_handle() {
        assertEquals("ana.lima", SocialProfiles.normalize(ProfileService.INSTAGRAM, " @ana.lima "))
        assertEquals("ana.lima", SocialProfiles.normalize(ProfileService.INSTAGRAM, "https://www.instagram.com/ana.lima/?igsh=abc"))
        assertEquals("NASA", SocialProfiles.normalize(ProfileService.X, "https://twitter.com/NASA"))
        assertEquals("@MrBeast", SocialProfiles.normalize(ProfileService.YOUTUBE, "MrBeast"))
        assertEquals("@MrBeast", SocialProfiles.normalize(ProfileService.YOUTUBE, "@MrBeast"))
        assertEquals("spez", SocialProfiles.normalize(ProfileService.REDDIT, "u/spez"))
        assertEquals("spez", SocialProfiles.normalize(ProfileService.REDDIT, "/u/spez"))
        assertEquals("ana-lima", SocialProfiles.normalize(ProfileService.LINKEDIN, "linkedin.com/in/ana-lima"))
        assertEquals("ana.bsky.social", SocialProfiles.normalize(ProfileService.BLUESKY, "@ana"))
        assertEquals("ana.example.org", SocialProfiles.normalize(ProfileService.BLUESKY, "ana.example.org"))
        assertEquals("ana@mastodon.social", SocialProfiles.normalize(ProfileService.MASTODON, "@ana@mastodon.social"))
        // Any server: the service is known from the row, not the address.
        assertEquals("ana@example.town", SocialProfiles.normalize(ProfileService.MASTODON, "https://example.town/@ana"))
        assertEquals("", SocialProfiles.normalize(ProfileService.GITHUB, "   "))
    }

    @Test fun handles_give_the_profile_address() {
        assertEquals("https://www.instagram.com/ana.lima/", Profile(ProfileService.INSTAGRAM, "ana.lima").url)
        assertEquals("https://www.linkedin.com/in/ana-lima/", Profile(ProfileService.LINKEDIN, "ana-lima").url)
        assertEquals("https://www.linkedin.com/company/acme/", Profile(ProfileService.LINKEDIN, "company/acme").url)
        assertEquals("https://x.com/ana_lima", Profile(ProfileService.X, "ana_lima").url)
        assertEquals("https://www.facebook.com/profile.php?id=100012345678901", Profile(ProfileService.FACEBOOK, "100012345678901").url)
        assertEquals("https://www.tiktok.com/@ana.lima", Profile(ProfileService.TIKTOK, "ana.lima").url)
        assertEquals("https://www.youtube.com/@MrBeast", Profile(ProfileService.YOUTUBE, "@MrBeast").url)
        assertEquals("https://www.snapchat.com/add/ana-lima", Profile(ProfileService.SNAPCHAT, "ana-lima").url)
        assertEquals("https://www.threads.net/@ana.lima", Profile(ProfileService.THREADS, "ana.lima").url)
        assertEquals("https://bsky.app/profile/ana.bsky.social", Profile(ProfileService.BLUESKY, "ana.bsky.social").url)
        assertEquals("https://mastodon.social/@ana", Profile(ProfileService.MASTODON, "ana@mastodon.social").url)
        assertEquals("https://github.com/analima", Profile(ProfileService.GITHUB, "analima").url)
        assertEquals("https://www.reddit.com/user/spez", Profile(ProfileService.REDDIT, "spez").url)
        assertEquals("https://www.twitch.tv/shroud", Profile(ProfileService.TWITCH, "shroud").url)
        // Every address Parley writes reads back as the same profile.
        for (s in ProfileService.entries) {
            val p = Profile(s, SocialProfiles.normalize(s, s.placeholder))
            assertEquals(s.name, p, SocialProfiles.fromWebsite(p.url, SocialProfiles.TYPE_CUSTOM, s.label))
            assertNull("${s.name} placeholder is valid", SocialProfiles.problem(s, s.placeholder))
        }
        assertEquals("", SocialProfiles.valueFor(ProfileService.INSTAGRAM, " @ "))
        assertEquals("https://x.com/ana", SocialProfiles.valueFor(ProfileService.X, "@ana"))
    }

    @Test fun shown_with_the_services_own_prefix() {
        assertEquals("@ana.lima", Profile(ProfileService.INSTAGRAM, "ana.lima").display)
        assertEquals("analima", Profile(ProfileService.GITHUB, "analima").display)
        assertEquals("u/spez", Profile(ProfileService.REDDIT, "spez").display)
        assertEquals("@ana@mastodon.social", Profile(ProfileService.MASTODON, "ana@mastodon.social").display)
        assertEquals("@MrBeast", Profile(ProfileService.YOUTUBE, "@MrBeast").display)
        assertEquals("acme", Profile(ProfileService.LINKEDIN, "company/acme").display)
        assertEquals(listOf("ana.lima", "@ana.lima"), SocialProfiles.searchTerms(Profile(ProfileService.INSTAGRAM, "ana.lima")))
    }

    @Test fun website_rows_are_profiles_by_label_or_by_address() {
        // Written by Parley: custom label naming the service (blank while being typed).
        assertEquals(Profile(ProfileService.INSTAGRAM, ""), SocialProfiles.fromWebsite("", 0, "Instagram"))
        assertEquals(Profile(ProfileService.MASTODON, "ana@example.town"), SocialProfiles.fromWebsite("https://example.town/@ana", 0, "Mastodon"))
        assertEquals(ProfileService.X, SocialProfiles.fromWebsite("https://twitter.com/ana", 0, "Twitter")?.service)
        // Written by another app with any type: recognised by the address.
        assertEquals(Profile(ProfileService.GITHUB, "torvalds"), SocialProfiles.fromWebsite("https://github.com/torvalds", 3, null))
        // A label that names no service, and an address of no service: an ordinary website.
        assertNull(SocialProfiles.fromWebsite("https://example.com", 0, "Shop"))
        assertNull(SocialProfiles.fromWebsite("https://example.com", 1, null))
        // A link under a service's label that isn't one of its profiles: what the address says, else a website.
        assertNull(SocialProfiles.fromWebsite("https://www.instagram.com/p/C1a2B3c4D5e/", 0, "Instagram"))
        assertEquals(Profile(ProfileService.GITHUB, "ana"), SocialProfiles.fromWebsite("https://github.com/ana", 0, "Instagram"))
        // A type other than custom never reads its label.
        assertNull(SocialProfiles.labelled(1, "Instagram"))
        assertEquals(ProfileService.LINKEDIN, SocialProfiles.labelled(0, "linkedin"))
    }

    @Test fun malformed_handles_get_a_hint() {
        assertEquals(ProfileProblem.FORMAT, SocialProfiles.problem(ProfileService.X, "this name is far too long"))
        assertEquals(ProfileProblem.FORMAT, SocialProfiles.problem(ProfileService.GITHUB, "-ana"))
        assertEquals(ProfileProblem.NEEDS_SERVER, SocialProfiles.problem(ProfileService.MASTODON, "ana"))
        assertNull(SocialProfiles.problem(ProfileService.MASTODON, "ana@mastodon.social"))
        assertNull(SocialProfiles.problem(ProfileService.INSTAGRAM, ""))
        assertNull(SocialProfiles.problem(ProfileService.LINKEDIN, "https://www.linkedin.com/in/ana-lima-0b1234567/"))
        assertEquals(ProfileProblem.FORMAT, SocialProfiles.problem(ProfileService.TWITCH, "ab"))
    }

    @Test fun vcard_social_profiles_from_ios_and_rfc_9554() {
        // iOS: X-SOCIALPROFILE;type=twitter;x-user=ana:http://twitter.com/ana
        assertEquals(Profile(ProfileService.X, "ana"), SocialProfiles.fromSocialProfile("twitter", "ana", "http\\://twitter.com/ana"))
        assertEquals(Profile(ProfileService.LINKEDIN, "ana-lima"), SocialProfiles.fromSocialProfile("linkedin", null, "http://www.linkedin.com/in/ana-lima"))
        // A handle only (iOS writes x-apple:<user> for services without an address).
        assertEquals(Profile(ProfileService.INSTAGRAM, "ana"), SocialProfiles.fromSocialProfile("instagram", "ana", "x-apple:ana"))
        // RFC 9554: SOCIALPROFILE;SERVICE-TYPE=Mastodon;USERNAME=ana:https://example.town/@ana
        assertEquals(Profile(ProfileService.MASTODON, "ana@example.town"), SocialProfiles.fromSocialProfile("Mastodon", "ana", "https://example.town/@ana"))
        assertNull(SocialProfiles.fromSocialProfile("myspace", "ana", "x-apple:ana"))
        assertNull(SocialProfiles.fromSocialProfile(null, null, null))
    }
}
