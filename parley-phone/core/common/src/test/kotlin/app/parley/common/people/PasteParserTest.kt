package app.parley.common.people

import app.parley.common.people.PasteParser.Card
import app.parley.common.people.PasteParser.Hint
import app.parley.common.people.PasteParser.HintType
import app.parley.common.people.PasteParser.Kind
import app.parley.common.people.PasteParser.Label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Paste details": real-world signatures, profiles, "Contact us" blocks and badges, read offline. */
@Suppress("LargeClass") // One test per kind of real-world text.
class PasteParserTest {
    private fun parse(text: String, region: String? = "GB", hints: List<Hint> = emptyList()) = PasteParser.parse(text.trimIndent(), region, hints)
    private fun one(text: String, region: String? = "GB", hints: List<Hint> = emptyList()): Card = parse(text, region, hints).single()
    private fun Card.v(k: Kind) = first(k)?.value
    private fun Card.vs(k: Kind) = all(k).map { it.value }
    private fun Card.labels(k: Kind) = all(k).map { it.label }

    // ---------------------------------------------------------------- English signatures

    @Test
    fun british_signature_with_pipes() {
        val c = one(
            """
            Best regards,

            Jane Doe
            Head of Partnerships | Acme Widgets Ltd
            M: +44 7911 123456 | T: +44 20 7946 0958
            jane.doe@acmewidgets.co.uk | www.acmewidgets.co.uk
            12 High Street, London SW1A 1AA, United Kingdom
            """,
        )
        assertEquals("Jane Doe", c.v(Kind.NAME))
        assertEquals("Acme Widgets Ltd", c.v(Kind.ORGANISATION))
        assertEquals("Head of Partnerships", c.v(Kind.JOB_TITLE))
        assertEquals(listOf("+447911123456", "+442079460958"), c.vs(Kind.PHONE))
        assertEquals(listOf(Label.MOBILE, Label.WORK), c.labels(Kind.PHONE))
        assertEquals(listOf("jane.doe@acmewidgets.co.uk"), c.vs(Kind.EMAIL))
        assertEquals(listOf("www.acmewidgets.co.uk"), c.vs(Kind.WEBSITE))
        val a = c.first(Kind.ADDRESS)!!.address!!
        assertEquals("12 High Street", a.street)
        assertEquals("London", a.city)
        assertEquals("SW1A 1AA", a.postcode)
        assertEquals("United Kingdom", a.country)
        assertEquals(Label.WORK, c.first(Kind.ADDRESS)!!.label)
        assertNull(c.first(Kind.NOTE))
    }

    @Test
    fun american_signature_with_suffix_extension_and_linkedin() {
        val c = one(
            """
            --
            John Smith, PhD
            Senior Software Engineer at Initech LLC
            1600 Amphitheatre Parkway
            Mountain View, CA 94043
            USA
            Office: (650) 253-0000 ext. 1234
            Cell: 650-555-0199
            john.smith@initech.com
            linkedin.com/in/johnsmith
            """,
            "US",
        )
        val n = c.first(Kind.NAME)!!.name!!
        assertEquals("John", n.given)
        assertEquals("Smith", n.family)
        assertEquals("PhD", n.suffix)
        assertEquals("Initech LLC", c.v(Kind.ORGANISATION))
        assertEquals("Senior Software Engineer", c.v(Kind.JOB_TITLE))
        assertEquals("+16502530000,1234", c.vs(Kind.PHONE)[0])
        assertEquals("1234", c.all(Kind.PHONE)[0].extension)
        assertEquals(listOf(Label.WORK, Label.MOBILE), c.labels(Kind.PHONE))
        val a = c.first(Kind.ADDRESS)!!.address!!
        assertEquals("1600 Amphitheatre Parkway", a.street)
        assertEquals("Mountain View", a.city)
        assertEquals("CA", a.region)
        assertEquals("94043", a.postcode)
        assertEquals("USA", a.country)
        assertEquals(ProfileService.LINKEDIN, c.first(Kind.PROFILE)!!.profile!!.service)
        assertEquals("johnsmith", c.first(Kind.PROFILE)!!.profile!!.handle)
    }

    @Test
    fun us_extension_written_with_x() {
        val c = one(
            """
            Sent from my iPhone
            Mike Ross
            Associate | Pearson Hardman
            mike.ross@pearsonhardman.com | 212.555.0147 x204
            """,
            "US",
        )
        assertEquals("Mike Ross", c.v(Kind.NAME))
        assertEquals("Pearson Hardman", c.v(Kind.ORGANISATION))
        assertEquals("Associate", c.v(Kind.JOB_TITLE))
        assertEquals("+12125550147,204", c.v(Kind.PHONE))
        assertEquals("204", c.first(Kind.PHONE)!!.extension)
    }

    @Test
    fun one_line_signature_with_dashes() {
        val c = one("Jane Doe — Founder & CEO, Bright Ideas Ltd — +44 7911 123456 — jane@brightideas.co.uk")
        assertEquals("Jane Doe", c.v(Kind.NAME))
        assertEquals("Founder & CEO", c.v(Kind.JOB_TITLE))
        assertEquals("Bright Ideas Ltd", c.v(Kind.ORGANISATION))
        assertEquals("+447911123456", c.v(Kind.PHONE))
        assertEquals(Label.MOBILE, c.first(Kind.PHONE)!!.label)
    }

    @Test
    fun organisation_from_the_email_domain_and_quote_as_note() {
        val c = one(
            """
            Tom Baker
            Product Designer
            Acme Widgets
            tom@acmewidgets.com
            "Design is how it works."
            This email and any attachments are confidential and intended solely for the use of the individual to whom it is addressed.
            """,
        )
        assertEquals("Tom Baker", c.v(Kind.NAME))
        assertEquals("Acme Widgets", c.v(Kind.ORGANISATION))
        assertEquals("Product Designer", c.v(Kind.JOB_TITLE))
        // The disclaimer is dropped; the quote is kept as a note.
        assertEquals("Design is how it works.", c.v(Kind.NOTE))
    }

    @Test
    fun sign_offs_and_sent_from_lines_are_dropped() {
        val c = one(
            """
            Kind regards,
            Cheers
            Sent from my Galaxy
            Get Outlook for Android
            Sam Patel
            sam.patel@example.org
            """,
        )
        assertEquals("Sam Patel", c.v(Kind.NAME))
        assertNull(c.first(Kind.NOTE))
    }

    @Test
    fun titles_like_dr_and_prof_are_prefixes() {
        val c = one(
            """
            Prof. Dr. Hans-Peter Müller
            Universität Beispielstadt
            Tel. +49 89 1234 5678
            """,
            "DE",
        )
        val n = c.first(Kind.NAME)!!.name!!
        assertEquals("Prof. Dr.", n.prefix)
        assertEquals("Hans-Peter", n.given)
        assertEquals("Müller", n.family)
        assertEquals("Universität Beispielstadt", c.v(Kind.ORGANISATION))
    }

    @Test
    fun middle_initial_and_particles() {
        assertEquals("J.", one("Mary J. Blige\n+1 212 736 3100", "US").first(Kind.NAME)!!.name!!.middle)
        val n = one("Pieter van der Berg\npieter@voorbeeld.nl", "NL").first(Kind.NAME)!!.name!!
        assertEquals("Pieter", n.given)
        assertEquals("van der Berg", n.family)
    }

    @Test
    fun all_caps_names_become_title_case() {
        val n = one("SEAN O'NEILL\n+44 7911 123456").first(Kind.NAME)!!.name!!
        assertEquals("Sean", n.given)
        assertEquals("O'Neill", n.family)
        assertEquals("Anne-Marie Smith", one("ANNE-MARIE SMITH\nanne@example.com").v(Kind.NAME))
    }

    @Test
    fun family_name_first_with_a_comma() {
        val c = one("DOE, Jane\nAcme Corp")
        assertEquals("Jane", c.first(Kind.NAME)!!.name!!.given)
        assertEquals("Doe", c.first(Kind.NAME)!!.name!!.family)
        assertEquals("Acme Corp", c.v(Kind.ORGANISATION))
    }

    @Test
    fun name_from_an_email_header() {
        val c = one("From: Jane Doe <jane.doe@example.com>\nPhone: +44 20 7946 0958")
        assertEquals("Jane Doe", c.v(Kind.NAME))
        assertEquals("jane.doe@example.com", c.v(Kind.EMAIL))
        assertNull(c.first(Kind.NOTE))
    }

    @Test
    fun name_from_the_email_address_when_nothing_else_names_them() {
        val c = one("jane.doe@gmail.com", null)
        assertEquals("Jane Doe", c.v(Kind.NAME))
        assertEquals(Label.HOME, c.first(Kind.EMAIL)!!.label)
        // A role mailbox names nobody.
        assertNull(one("info@acme.com\n+44 20 7946 0958").first(Kind.NAME))
    }

    @Test
    fun labelled_fields() {
        val c = one(
            """
            Name: Ana Lima
            Company: Lima & Filhos
            Job title: Owner
            Email: ana@limafilhos.com.br
            Phone: +55 11 91234-5678
            Address: Rua Augusta 100, 01304-000 São Paulo
            Notes: Met at the trade fair
            """,
            "BR",
        )
        assertEquals("Ana Lima", c.v(Kind.NAME))
        assertEquals("Lima & Filhos", c.v(Kind.ORGANISATION))
        assertEquals("Owner", c.v(Kind.JOB_TITLE))
        assertEquals("+5511912345678", c.v(Kind.PHONE))
        assertEquals("Rua Augusta 100", c.first(Kind.ADDRESS)!!.address!!.street)
        assertEquals("São Paulo", c.first(Kind.ADDRESS)!!.address!!.city)
        assertEquals("Met at the trade fair", c.v(Kind.NOTE))
    }

    @Test
    fun address_label_on_its_own_line_takes_the_next_lines() {
        val c = one(
            """
            Jane Doe
            Address:
            The Old Mill
            Mill Lane
            Little Snoring

            jane@example.com
            """,
        )
        val a = c.first(Kind.ADDRESS)!!.address!!
        assertEquals("The Old Mill\nMill Lane", a.street)
        assertEquals("Little Snoring", a.city)
    }

    // ---------------------------------------------------------------- European signatures

    @Test
    fun german_signature() {
        val c = one(
            """
            Mit freundlichen Grüßen

            Dr. Max Mustermann
            Geschäftsführer
            Beispiel GmbH
            Hauptstraße 5
            10115 Berlin
            Tel.: +49 30 1234567
            Fax: +49 30 1234568
            Mobil: 0151 23456789
            E-Mail: max.mustermann@beispiel.de
            Web: www.beispiel.de
            """,
            "DE",
        )
        assertEquals("Dr.", c.first(Kind.NAME)!!.name!!.prefix)
        assertEquals("Max", c.first(Kind.NAME)!!.name!!.given)
        assertEquals("Beispiel GmbH", c.v(Kind.ORGANISATION))
        assertEquals("Geschäftsführer", c.v(Kind.JOB_TITLE))
        assertEquals(listOf("+49301234567", "+49301234568", "+4915123456789"), c.vs(Kind.PHONE))
        assertEquals(listOf(Label.WORK, Label.FAX, Label.MOBILE), c.labels(Kind.PHONE))
        val a = c.first(Kind.ADDRESS)!!.address!!
        assertEquals("Hauptstraße 5", a.street)
        assertEquals("10115", a.postcode)
        assertEquals("Berlin", a.city)
        assertEquals("www.beispiel.de", c.v(Kind.WEBSITE))
        assertNull(c.first(Kind.NOTE))
    }

    @Test
    fun german_compound_title_and_swiss_address() {
        val c = one(
            """
            Lukas Meier
            Projektleiter
            Muster AG
            Bahnhofstrasse 1
            8001 Zürich
            Schweiz
            +41 44 668 18 00
            """,
            "CH",
        )
        assertEquals("Projektleiter", c.v(Kind.JOB_TITLE))
        assertEquals("Muster AG", c.v(Kind.ORGANISATION))
        val a = c.first(Kind.ADDRESS)!!.address!!
        assertEquals("Bahnhofstrasse 1", a.street)
        assertEquals("Zürich", a.city)
        assertEquals("Schweiz", a.country)
    }

    @Test
    fun french_signature_with_capital_family_name() {
        val c = one(
            """
            Cordialement,
            Marie DUPONT
            Directrice commerciale
            Société Exemple SAS
            12 rue de Rivoli, 75001 Paris
            Tél. : 01 42 68 53 00
            Portable : 06 12 34 56 78
            marie.dupont@exemple.fr
            """,
            "FR",
        )
        assertEquals("Marie Dupont", c.v(Kind.NAME))
        assertEquals("Société Exemple SAS", c.v(Kind.ORGANISATION))
        assertEquals("Directrice commerciale", c.v(Kind.JOB_TITLE))
        assertEquals(listOf("+33142685300", "+33612345678"), c.vs(Kind.PHONE))
        assertEquals(listOf(Label.WORK, Label.MOBILE), c.labels(Kind.PHONE))
        assertEquals("75001", c.first(Kind.ADDRESS)!!.address!!.postcode)
    }

    @Test
    fun spanish_signature() {
        val c = one(
            """
            Atentamente,
            Carlos García López
            Director de Ventas
            Empresa Ejemplo S.L.
            Calle Mayor 10, 28013 Madrid
            Tel: +34 912 345 678
            Móvil: +34 612 345 678
            carlos.garcia@ejemplo.es
            """,
            "ES",
        )
        assertEquals("Carlos García López", c.v(Kind.NAME))
        assertEquals("Empresa Ejemplo S.L.", c.v(Kind.ORGANISATION))
        assertEquals("Director de Ventas", c.v(Kind.JOB_TITLE))
        assertEquals(listOf(Label.WORK, Label.MOBILE), c.labels(Kind.PHONE))
        assertEquals("Madrid", c.first(Kind.ADDRESS)!!.address!!.city)
    }

    @Test
    fun italian_signature() {
        val c = one(
            """
            Cordiali saluti
            Dott.ssa Giulia Rossi
            Responsabile Marketing
            Esempio S.r.l.
            Via Roma 1, 00184 Roma
            Cell. +39 347 123 4567
            giulia.rossi@esempio.it
            """,
            "IT",
        )
        assertEquals("Dott.ssa", c.first(Kind.NAME)!!.name!!.prefix)
        assertEquals("Giulia", c.first(Kind.NAME)!!.name!!.given)
        assertEquals("Esempio S.r.l.", c.v(Kind.ORGANISATION))
        assertEquals("Responsabile Marketing", c.v(Kind.JOB_TITLE))
        assertEquals(Label.MOBILE, c.first(Kind.PHONE)!!.label)
        assertEquals("00184", c.first(Kind.ADDRESS)!!.address!!.postcode)
    }

    @Test
    fun dutch_signature_with_single_letter_labels() {
        val c = one(
            """
            Met vriendelijke groet,
            Pieter van der Berg
            Adviseur
            Voorbeeld B.V.
            Keizersgracht 123
            1015 CJ Amsterdam
            T +31 20 123 4567
            M +31 6 12345678
            pieter@voorbeeld.nl
            """,
            "NL",
        )
        assertEquals("Voorbeeld B.V.", c.v(Kind.ORGANISATION))
        assertEquals("Adviseur", c.v(Kind.JOB_TITLE))
        assertEquals(listOf(Label.WORK, Label.MOBILE), c.labels(Kind.PHONE))
        val a = c.first(Kind.ADDRESS)!!.address!!
        assertEquals("Keizersgracht 123", a.street)
        assertEquals("1015 CJ", a.postcode)
        assertEquals("Amsterdam", a.city)
    }

    // ---------------------------------------------------------------- numbers

    @Test
    fun national_numbers_use_the_sim_country() {
        assertEquals("+923001234567", one("Ahmed Khan\n0300 1234567", "PK").v(Kind.PHONE))
        assertEquals("+4915123456789", one("Max Muster\n0151 23456789", "DE").v(Kind.PHONE))
        assertEquals("+447911123456", one("Jane Doe\n07911 123456", "GB").v(Kind.PHONE))
    }

    @Test
    fun international_numbers_keep_their_country() {
        val c = one("Jane Doe\n+1 650 253 0000\n+91 98765 43210\n+971 50 123 4567", "GB")
        assertEquals(listOf("+16502530000", "+919876543210", "+971501234567"), c.vs(Kind.PHONE))
    }

    @Test
    fun numbers_without_a_country_hint_need_a_plus() {
        val c = one("Jane Doe\n020 7946 0958\n+44 7911 123456", null)
        assertEquals(listOf("+447911123456"), c.vs(Kind.PHONE))
    }

    @Test
    fun trunk_zero_in_brackets() {
        assertEquals("+442079460958", one("Jane Doe\nPhone: +44 (0)20 7946 0958").v(Kind.PHONE))
    }

    @Test
    fun label_after_the_number() {
        val c = one("Jane Doe\n+44 7911 123456 (mobile)\n+44 20 7946 0958 (work)")
        assertEquals(listOf(Label.MOBILE, Label.WORK), c.labels(Kind.PHONE))
        assertNull(c.first(Kind.NOTE))
    }

    @Test
    fun two_numbers_on_one_labelled_line() {
        val c = one("Jane Doe\nTel.: +49 30 1234567, Fax: +49 30 1234568", "DE")
        assertEquals(listOf(Label.WORK, Label.FAX), c.labels(Kind.PHONE))
    }

    @Test
    fun toll_free_is_the_main_number() {
        val c = one("Acme Support\nToll free: +1 800 555 0199\nsupport@acme.com", "US")
        assertEquals(Label.MAIN, c.first(Kind.PHONE)!!.label)
        assertEquals("Acme Support", c.v(Kind.ORGANISATION))
    }

    @Test
    fun postcodes_dates_and_registration_numbers_are_not_phones() {
        val c = one(
            """
            Jane Doe
            Registered in England No. 01234567
            10115 Berlin
            12.03.2024
            jane@example.com
            """,
        )
        assertTrue(c.vs(Kind.PHONE).isEmpty())
    }

    @Test
    fun repeated_numbers_are_listed_once() {
        assertEquals(1, one("Jane Doe\n+44 7911 123456\n07911 123456").vs(Kind.PHONE).size)
    }

    // ---------------------------------------------------------------- addresses and map links

    @Test
    fun british_address_over_three_lines() {
        val c = one(
            """
            Contact us
            Acme Bakery Ltd
            22 Baker Street
            London
            NW1 6XE
            Phone: 020 7946 0000
            Email: info@acmebakery.co.uk
            Open Mon-Fri 8am-6pm
            """,
        )
        assertNull(c.first(Kind.NAME))
        assertEquals("Acme Bakery Ltd", c.v(Kind.ORGANISATION))
        val a = c.first(Kind.ADDRESS)!!.address!!
        assertEquals("22 Baker Street", a.street)
        assertEquals("London", a.city)
        assertEquals("NW1 6XE", a.postcode)
        assertEquals("Open Mon-Fri 8am-6pm", c.v(Kind.NOTE))
    }

    @Test
    fun us_address_on_one_line() {
        val c = one("Mary J. Blige\n350 Fifth Avenue, New York, NY 10118\n(212) 736-3100", "US")
        val a = c.first(Kind.ADDRESS)!!.address!!
        assertEquals("350 Fifth Avenue", a.street)
        assertEquals("New York", a.city)
        assertEquals("NY", a.region)
        assertEquals("10118", a.postcode)
        assertEquals("350 Fifth Avenue, New York, NY 10118", c.v(Kind.ADDRESS))
    }

    @Test
    fun canadian_and_australian_addresses() {
        val ca = one("Pat Lee\n100 Queen St W, Toronto, ON M5H 2N2\n416-555-0123", "CA").first(Kind.ADDRESS)!!.address!!
        assertEquals("100 Queen St W", ca.street)
        assertEquals("Toronto", ca.city)
        assertEquals("ON", ca.region)
        assertEquals("M5H 2N2", ca.postcode)
        val au = one("Kylie Jones\nLevel 5, 100 George Street, Sydney NSW 2000\n0412 345 678", "AU").first(Kind.ADDRESS)!!.address!!
        assertEquals("Level 5, 100 George Street", au.street)
        assertEquals("Sydney", au.city)
        assertEquals("NSW", au.region)
        assertEquals("2000", au.postcode)
    }

    @Test
    fun pakistani_and_indian_addresses() {
        val pk = one("Ahmed Khan\nKhan Traders (Pvt) Ltd\nHouse 12, Street 5, F-7/2, Islamabad\n0300 1234567", "PK")
        assertEquals("Khan Traders (Pvt) Ltd", pk.v(Kind.ORGANISATION))
        assertEquals("Islamabad", pk.first(Kind.ADDRESS)!!.address!!.city)
        val inAddr = one("Priya Sharma\nSenior Consultant\nTata Consultancy Services\n+91 98765 43210\nMumbai 400001", "IN").first(Kind.ADDRESS)!!.address!!
        assertEquals("Mumbai", inAddr.city)
        assertEquals("400001", inAddr.postcode)
    }

    @Test
    fun organisation_and_address_on_one_line() {
        val c = one("Jane Doe\nAcme Ltd, 12 High Street, London SW1A 1AA\njane@acme.co.uk")
        assertEquals("Acme Ltd", c.v(Kind.ORGANISATION))
        assertEquals("12 High Street", c.first(Kind.ADDRESS)!!.address!!.street)
    }

    @Test
    fun map_links_are_read_offline() {
        val c = one("Jane Doe\nFind us: https://www.google.com/maps/place/Big+Ben/@51.5007292,-0.1268141,17z\njane@example.com")
        val place = c.first(Kind.MAP_LINK)!!.place!!
        assertEquals(51.5007292, place.lat!!, 1e-6)
        assertTrue(c.vs(Kind.WEBSITE).isEmpty())
        // A short link stays as it is (only its server knows where it points).
        val short = one("Eiffel Tower\nAv. Gustave Eiffel, 75007 Paris, France\nhttps://maps.app.goo.gl/xyz123", "FR")
        assertTrue(short.first(Kind.MAP_LINK)!!.place!!.needsNetwork)
    }

    @Test
    fun geo_uri_and_osm_links() {
        val c = one("Hut 7\ngeo:46.5763,7.9904?q=46.5763,7.9904(Hut)\nhttps://www.openstreetmap.org/?mlat=46.5&mlon=7.9\nhut@example.ch", "CH")
        assertEquals(2, c.all(Kind.MAP_LINK).size)
    }

    @Test
    fun classifier_address_hint_is_used_where_the_rules_see_none() {
        val text = "Jane Doe\nThe Old Mill\nMill Lane\nLittle Snoring\njane@example.com"
        assertNull(one(text).first(Kind.ADDRESS))
        val start = text.indexOf("The Old Mill")
        val end = text.indexOf("\njane")
        val c = one(text, hints = listOf(Hint(start, end, HintType.ADDRESS)))
        val a = c.first(Kind.ADDRESS)!!.address!!
        assertEquals("Little Snoring", a.city)
        assertEquals("Jane Doe", c.v(Kind.NAME))
    }

    @Test
    fun classifier_phone_hint_accepts_a_number_the_rules_doubt() {
        val text = "Jane Doe\n01632 960123"
        assertTrue(one(text).vs(Kind.PHONE).isEmpty())
        val start = text.indexOf("01632")
        assertEquals(listOf("+441632960123"), one(text, hints = listOf(Hint(start, text.length, HintType.PHONE))).vs(Kind.PHONE))
    }

    @Test
    fun bad_hints_are_ignored() {
        val c = one("Jane Doe\n+44 7911 123456", hints = listOf(Hint(-3, 400, HintType.ADDRESS), Hint(5, 2, HintType.PHONE)))
        assertEquals("Jane Doe", c.v(Kind.NAME))
    }

    // ---------------------------------------------------------------- web, social, emoji

    @Test
    fun emoji_bullets_say_what_follows() {
        val c = one(
            """
            📞 +44 20 7946 0958
            📱 07911 123456
            ✉️ hello@studio.design
            🌐 studio.design
            📍 5 Canal Street, Manchester M1 3HE
            """,
        )
        assertEquals(listOf(Label.HOME, Label.MOBILE), c.labels(Kind.PHONE))
        assertEquals("hello@studio.design", c.v(Kind.EMAIL))
        assertEquals("studio.design", c.v(Kind.WEBSITE))
        assertEquals("Manchester", c.first(Kind.ADDRESS)!!.address!!.city)
    }

    @Test
    fun emoji_around_a_name_are_dropped() {
        assertEquals("Lena Fischer", one("✨ Lena Fischer ✨\n+49 151 23456789", "DE").v(Kind.NAME))
    }

    @Test
    fun social_handles_with_their_service() {
        val c = one(
            """
            Ana Lima
            Instagram @ana.lima
            Twitter: @analima
            Mastodon: @ana@mastodon.social
            @analima on Bluesky
            https://github.com/analima
            """,
            null,
        )
        val services = c.all(Kind.PROFILE).map { it.profile!!.service }
        assertEquals(listOf(ProfileService.INSTAGRAM, ProfileService.X, ProfileService.MASTODON, ProfileService.BLUESKY, ProfileService.GITHUB), services)
        assertTrue(c.vs(Kind.EMAIL).isEmpty())
        assertEquals("https://mastodon.social/@ana", c.all(Kind.PROFILE)[2].value)
    }

    @Test
    fun profile_links_are_profiles_and_other_links_websites() {
        val c = one("Ana Lima\nhttps://www.instagram.com/ana.lima/?igsh=abc\nhttps://analima.dev/blog", null)
        assertEquals("ana.lima", c.first(Kind.PROFILE)!!.profile!!.handle)
        assertEquals(listOf("https://analima.dev/blog"), c.vs(Kind.WEBSITE))
    }

    @Test
    fun obfuscated_email() {
        assertEquals("jane@example.com", one("Jane Doe\njane [at] example [dot] com").v(Kind.EMAIL))
    }

    @Test
    fun email_in_brackets_and_mailto() {
        val c = one("Jane Doe <mailto:jane@example.com>\nW: acme.com")
        assertEquals("jane@example.com", c.v(Kind.EMAIL))
        assertEquals("acme.com", c.v(Kind.WEBSITE))
        assertNull(c.first(Kind.NOTE))
    }

    @Test
    fun sentence_punctuation_after_a_link_is_not_part_of_it() {
        assertEquals("https://acme.com/team", one("Jane Doe\nSee https://acme.com/team.").v(Kind.WEBSITE))
    }

    @Test
    fun business_profile_from_a_chat_app() {
        val c = one(
            """
            Sunrise Café
            Café · Lahore
            +92 42 35761234
            Mon–Sun 8:00 AM – 11:00 PM
            sunrisecafe.pk
            12 MM Alam Road, Gulberg III, Lahore 54000
            """,
            "PK",
        )
        assertNull(c.first(Kind.NAME))
        assertEquals("Sunrise Café", c.v(Kind.ORGANISATION))
        assertEquals("Lahore", c.first(Kind.ADDRESS)!!.address!!.city)
        assertEquals("sunrisecafe.pk", c.v(Kind.WEBSITE))
        assertEquals("Café, Lahore\nMon–Sun 8:00 AM – 11:00 PM", c.v(Kind.NOTE))
    }

    // ---------------------------------------------------------------- badges, scripts, several people

    @Test
    fun event_badge() {
        val c = one(
            """
            HELLO my name is
            JANE DOE
            ACME CORP
            Speaker
            """,
            null,
        )
        assertEquals("Jane Doe", c.v(Kind.NAME))
        assertEquals("ACME CORP", c.v(Kind.ORGANISATION))
        assertEquals("Speaker", c.v(Kind.JOB_TITLE))
    }

    @Test
    fun badge_with_family_name_first_and_attendee_line() {
        val c = one("DEVCONF 2026\nGARCÍA, Lucía\nAttendee\nlucia@example.es", "ES")
        assertEquals("Lucía García", c.v(Kind.NAME))
    }

    @Test
    fun arabic_name_and_title() {
        val c = one("محمد أحمد\nمدير المبيعات\n+971 50 123 4567\nmohammed@example.ae", null)
        val n = c.first(Kind.NAME)!!.name!!
        assertEquals("محمد", n.given)
        assertEquals("أحمد", n.family)
        assertEquals("مدير المبيعات", c.v(Kind.JOB_TITLE))
    }

    @Test
    fun arabic_compound_name() {
        val n = one("عبد الله خان\n+92 300 1234567", null).first(Kind.NAME)!!.name!!
        assertEquals("عبد الله", n.given)
        assertEquals("خان", n.family)
    }

    @Test
    fun hebrew_name_with_invisible_direction_marks() {
        val c = one("‏דוד כהן‏\n‪+972 52-123-4567‬", "IL")
        assertEquals("דוד כהן", c.v(Kind.NAME))
        assertEquals("+972521234567", c.v(Kind.PHONE))
    }

    @Test
    fun cjk_name_in_one_word() {
        val c = one("山田太郎\n+81 90-1234-5678\ntaro.yamada@example.jp", "JP")
        assertEquals("山田太郎", c.v(Kind.NAME))
        assertNull(c.first(Kind.NOTE))
    }

    @Test
    fun two_people_separated_by_a_blank_line() {
        val cards = parse("Jane Doe\njane@example.com\n+44 7911 123456\n\nJohn Roe\njohn@example.com\n+44 7911 654321")
        assertEquals(listOf("Jane Doe", "John Roe"), cards.map { it.title })
        assertEquals("+447911654321", cards[1].v(Kind.PHONE))
    }

    @Test
    fun a_team_list_without_blank_lines() {
        val cards = parse(
            """
            Alice Martin
            CEO
            alice@startup.io
            +1 415 555 0101
            Bob Chen
            CTO
            bob@startup.io
            +1 415 555 0102
            """,
            "US",
        )
        assertEquals(2, cards.size)
        assertEquals("CTO", cards[1].v(Kind.JOB_TITLE))
        assertEquals("bob@startup.io", cards[1].v(Kind.EMAIL))
    }

    @Test
    fun a_place_name_after_the_contact_lines_is_not_another_person() {
        val cards = parse("Jane Doe\n+44 7911 123456\nSan Francisco\nGolden Gate", "US")
        assertEquals(1, cards.size)
        assertEquals("Jane Doe", cards[0].v(Kind.NAME))
    }

    // ---------------------------------------------------------------- birthdays

    @Test
    fun birthdays_only_when_the_text_says_so() {
        assertEquals("1990-03-12", one("Jane Roe\nDOB: 03/12/1990\njane.roe@gmail.com", "US").v(Kind.BIRTHDAY))
        assertEquals("1990-12-03", one("Jane Roe\nDOB: 03/12/1990", "GB").v(Kind.BIRTHDAY))
        assertEquals("1985-05-05", one("Sam Taylor\nBorn 5th May 1985").v(Kind.BIRTHDAY))
        assertEquals("--12-24", one("Sam Taylor\nBirthday: 24 Dec").v(Kind.BIRTHDAY))
        assertEquals("1990-03-12", one("Lena Fischer\nGeburtstag: 12. März 1990", "DE").v(Kind.BIRTHDAY))
        assertEquals("1979-07-01", one("Ana Lima\nBirthday: July 1st, 1979").v(Kind.BIRTHDAY))
        assertEquals("1990-03-12", one("Ana Lima\n🎂 1990-03-12").v(Kind.BIRTHDAY))
        // A date without its word is a meeting, not a birthday.
        assertNull(one("Ana Lima\n12 March 1990\nana@example.com").first(Kind.BIRTHDAY))
    }

    @Test
    fun impossible_dates_are_not_birthdays() {
        assertNull(PasteDates.parse("31/02/1990", "GB"))
        assertNull(PasteDates.parse("29.02.2023", "DE"))
        assertEquals("2024-02-29", PasteDates.parse("29.02.2024", "DE"))
        assertEquals("--02-29", PasteDates.parse("29 Feb", "GB"))
        assertEquals("2005-01-31", PasteDates.parse("31/01/05", "GB"))
    }

    // ---------------------------------------------------------------- when to offer it

    @Test
    fun offered_for_contact_details_not_for_a_lone_number_or_link() {
        assertTrue(PasteParser.worthOffering("Jane Doe\n+44 7911 123456", "GB"))
        assertTrue(PasteParser.worthOffering("Jane Doe — jane@example.com", "GB"))
        assertFalse(PasteParser.worthOffering("+44 7911 123456", "GB"))
        assertFalse(PasteParser.worthOffering("https://maps.app.goo.gl/xyz123", "GB"))
        assertFalse(PasteParser.worthOffering("Call me on 07911 123456 when you're free, thanks!", "GB"))
        assertFalse(PasteParser.worthOffering("", "GB"))
    }

    @Test
    fun a_chatty_message_keeps_its_text_as_a_note() {
        val c = one("Hi! It was great meeting you at the conference. Call me on 07911 123456 when you're free. — Sam")
        assertEquals("+447911123456", c.v(Kind.PHONE))
        assertNull(c.first(Kind.NAME))
        assertTrue(c.v(Kind.NOTE)!!.contains("great meeting you"))
    }

    @Test
    fun long_leftovers_are_offered_unticked() {
        val long = "Our studio works with brands across Europe on identity, packaging and digital products since 2009. ".repeat(3)
        val c = one("Jane Doe\njane@example.com\n$long")
        assertFalse(c.first(Kind.NOTE)!!.suggested)
        assertTrue(c.first(Kind.NAME)!!.suggested)
    }

    @Test
    fun nothing_to_read() {
        assertTrue(PasteParser.parse("   \n\n ", "GB").isEmpty())
        assertTrue(PasteParser.parse("Kind regards,\n--\nSent from my iPhone", "GB").isEmpty())
    }

    @Test
    fun very_long_text_is_cut() {
        val text = "Jane Doe\n+44 7911 123456\n" + "x".repeat(PasteParser.MAX_TEXT * 2)
        assertEquals("Jane Doe", PasteParser.parse(text, "GB").first().v(Kind.NAME))
    }

    @Test
    fun sentences_are_not_names() {
        assertNull(PasteNames.parse("Thanks for your time"))
        assertNull(PasteNames.parse("jane doe"))
        assertNull(PasteNames.parse("Customer Service Team"))
        assertNull(PasteNames.parse("New York"))
        assertNull(PasteNames.parse("Germany"))
        assertEquals("Jane", PasteNames.parse("Jane")!!.given)
    }

    @Test
    fun organisation_forms() {
        listOf(
            "Acme Ltd", "Acme Ltd.", "Widgets LLC", "Initech Inc.", "Beispiel GmbH", "Muster AG", "Exemple SAS", "Esempio S.r.l.", "Voorbeeld B.V.",
            "Foo plc", "Bar Pty Ltd",
        ).forEach { assertTrue(it, PasteWords.isOrg(it)) }
        listOf("Jane Doe", "Head of Sales", "London").forEach { assertFalse(it, PasteWords.isOrg(it)) }
    }

    @Test
    fun job_titles() {
        listOf("CEO", "Head of Sales", "Senior Software Engineer", "Vertriebsleiterin", "Directrice commerciale", "Gerente General", "Co-founder")
            .forEach { assertTrue(it, PasteWords.isTitle(it)) }
        listOf("Head Office", "Jane Doe", "Acme Corp").forEach { assertFalse(it, PasteWords.isTitle(it)) }
    }
}
