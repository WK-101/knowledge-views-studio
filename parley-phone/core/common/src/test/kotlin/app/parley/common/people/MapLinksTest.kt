package app.parley.common.people

import app.parley.common.people.MapLinks.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLinksTest {
    private fun place(text: String): MapLinks.Place {
        val p = MapLinks.parse(text)
        assertNotNull("no place in $text", p)
        return p!!
    }

    private fun assertAt(p: MapLinks.Place, lat: Double, lon: Double, tolerance: Double = 1e-5) {
        assertEquals("lat of ${p.link}", lat, p.lat!!, tolerance)
        assertEquals("lon of ${p.link}", lon, p.lon!!, tolerance)
    }

    // ---------------------------------------------------------------- geo:

    @Test fun geo_uri_with_label() {
        val p = place("geo:48.8584,2.2945?q=48.8584,2.2945(Eiffel Tower)")
        assertEquals(Service.GEO, p.service)
        assertAt(p, 48.8584, 2.2945)
        assertEquals("Eiffel Tower", p.name)
    }

    @Test fun geo_uri_forms() {
        assertAt(place("geo:37.786971,-122.399677"), 37.786971, -122.399677)
        assertAt(place("geo:37.786971,-122.399677,250;crs=wgs84;u=35?z=17"), 37.786971, -122.399677)
        // The position in q wins over a 0,0 placeholder, and an encoded label is decoded.
        val p = place("geo:0,0?q=51.5007,-0.1246(Big%20Ben)")
        assertAt(p, 51.5007, -0.1246)
        assertEquals("Big Ben", p.name)
    }

    @Test fun geo_search_has_a_name_and_no_position() {
        val p = place("geo:0,0?q=1600+Amphitheatre+Parkway%2C+Mountain+View%2C+CA")
        assertFalse(p.hasCoordinates)
        assertEquals("1600 Amphitheatre Parkway, Mountain View, CA", p.name)
    }

    // ---------------------------------------------------------------- Google Maps

    @Test fun google_place_link_prefers_the_pin_over_the_view() {
        val p = place(
            "https://www.google.com/maps/place/Eiffel+Tower/@48.8583701,2.2922926,17z/data=!3m1!4b1!4m6!3m5" +
                "!1s0x47e66e2964e34e2d:0x8ddca9ee380ef7e0!8m2!3d48.8583701!4d2.2944813!16zL20vMDJqODE",
        )
        assertEquals(Service.GOOGLE, p.service)
        assertAt(p, 48.8583701, 2.2944813)
        assertEquals("Eiffel Tower", p.name)
        assertFalse(p.needsNetwork)
    }

    @Test fun google_place_without_pin_uses_the_view_centre() {
        val p = place("https://www.google.co.uk/maps/place/Buckingham+Palace,+London+SW1A+1AA/@51.501364,-0.14189,17z")
        assertAt(p, 51.501364, -0.14189)
        assertEquals("Buckingham Palace, London SW1A 1AA", p.name)
    }

    @Test fun google_query_forms() {
        assertAt(place("https://maps.google.com/?q=40.748817,-73.985428"), 40.748817, -73.985428)
        assertAt(place("https://maps.google.com/?ll=40.748817,-73.985428&z=15"), 40.748817, -73.985428)
        assertAt(place("https://www.google.com/maps?q=loc:-33.8568,151.2153"), -33.8568, 151.2153)
        assertAt(place("https://www.google.com/maps/search/?api=1&query=47.5951518%2C-122.3316393"), 47.5951518, -122.3316393)
        assertAt(place("https://www.google.com/maps/@?api=1&map_action=map&center=-33.712206,150.311941&zoom=12"), -33.712206, 150.311941)
        assertAt(place("https://www.google.com/maps/dir/?api=1&destination=48.1374,11.5755"), 48.1374, 11.5755)
        val named = place("https://www.google.com/maps/search/?api=1&query=Lumen+Field")
        assertFalse(named.hasCoordinates)
        assertEquals("Lumen Field", named.name)
    }

    @Test fun google_dropped_pin_isnt_a_name() {
        val p = place("https://www.google.com/maps/place/48%C2%B051'30.1%22N+2%C2%B017'40.2%22E/@48.8583611,2.2945,17z")
        assertAt(p, 48.8583611, 2.2945)
        assertNull(p.name)
    }

    @Test fun google_short_links_need_the_internet_and_keep_the_shared_name() {
        val p = place("Eiffel Tower\nAv. Gustave Eiffel, 75007 Paris, France\nhttps://maps.app.goo.gl/2G6Wb1cVGpJ7t5Xw8")
        assertTrue(p.needsNetwork)
        assertFalse(p.hasCoordinates)
        assertEquals("https://maps.app.goo.gl/2G6Wb1cVGpJ7t5Xw8", p.link)
        assertEquals("Eiffel Tower, Av. Gustave Eiffel, 75007 Paris, France", p.name)
        assertTrue(place("https://goo.gl/maps/abcdEFGH123").needsNetwork)
        // Not a map link: goo.gl without /maps is an ordinary short link.
        assertFalse(place("https://goo.gl/abcd").needsNetwork)
    }

    // ---------------------------------------------------------------- OpenStreetMap

    @Test fun openstreetmap_marker_and_map_fragment() {
        val p = place("https://www.openstreetmap.org/?mlat=52.51628&mlon=13.37771#map=17/52.51628/13.37771")
        assertEquals(Service.OPENSTREETMAP, p.service)
        assertAt(p, 52.51628, 13.37771)
        assertAt(place("https://www.openstreetmap.org/#map=15/-33.8688/151.2093"), -33.8688, 151.2093)
        assertAt(place("https://www.openstreetmap.org/node/123456#map=19/41.89021/12.49223&layers=N"), 41.89021, 12.49223)
    }

    @Test fun openstreetmap_short_code_decodes_offline() {
        // The short code OpenStreetMap's own tests use for 51.5110, 0.0550 at zoom 9.
        assertAt(place("https://osm.org/go/0EEQjE--"), 51.5110, 0.0550, tolerance = 0.01)
        assertAt(place("https://www.openstreetmap.org/go/0BOdUtpMh-?m="), 48.85837, 2.29448, tolerance = 0.001)
        assertAt(place("osm.org/go/uN~Ra6l7--"), -33.8568, 151.2153, tolerance = 0.01)
        // The old "@" spelling of "~".
        assertAt(place("https://osm.org/go/uN@Ra6l7--"), -33.8568, 151.2153, tolerance = 0.01)
    }

    // ---------------------------------------------------------------- Organic Maps, CoMaps, MAPS.ME

    @Test fun ge0_codes_decode_offline() {
        // Organic Maps' own test vectors.
        val p = place("https://omaps.app/B4srhdHVVt/Some_Name")
        assertEquals(Service.ORGANIC_MAPS, p.service)
        assertAt(p, 64.5234, 12.1234, tolerance = 1e-4)
        assertEquals("Some Name", p.name)
        assertAt(place("ge0://Byqqqqqqqq/Name"), 45.0, 0.0, tolerance = 1e-4)
        assertAt(place("https://ge0.me/B4srhdHVVt/Some_Name"), 64.5234, 12.1234, tolerance = 1e-4)
        assertAt(place("Look: https://comaps.at/B4srhdHVVt/Caf%C3%A9_Central"), 64.5234, 12.1234, tolerance = 1e-4)
        assertEquals("Café Central", place("https://comaps.at/B4srhdHVVt/Caf%C3%A9_Central").name)
    }

    @Test fun organic_maps_parameter_links() {
        val p = place("om://map?v=1&ll=54.32123,12.34562&n=Point%20Name")
        assertAt(p, 54.32123, 12.34562)
        assertEquals("Point Name", p.name)
    }

    // ---------------------------------------------------------------- OsmAnd, Magic Earth, Apple Maps

    @Test fun osmand_links() {
        val p = place("https://osmand.net/go?lat=59.93863&lon=30.31413&z=16&name=Hermitage")
        assertEquals(Service.OSMAND, p.service)
        assertAt(p, 59.93863, 30.31413)
        assertEquals("Hermitage", p.name)
        assertAt(place("https://osmand.net/map?pin=52.51628,13.37771#15/52.5163/13.3777"), 52.51628, 13.37771)
        assertAt(place("https://osmand.net/map#15/52.5163/13.3777"), 52.5163, 13.3777)
    }

    @Test fun magic_earth_links() {
        val p = place("magicearth://?show_on_map&lat=48.20849&lon=16.37208&name=Stephansdom")
        assertEquals(Service.MAGIC_EARTH, p.service)
        assertAt(p, 48.20849, 16.37208)
        assertEquals("Stephansdom", p.name)
        assertAt(place("https://magicearth.com/?lat=48.20849&lon=16.37208"), 48.20849, 16.37208)
    }

    @Test fun apple_maps_links() {
        val p = place("https://maps.apple.com/?ll=37.3349,-122.00902&q=Apple%20Park")
        assertEquals(Service.APPLE, p.service)
        assertAt(p, 37.3349, -122.00902)
        assertEquals("Apple Park", p.name)
        assertAt(place("https://maps.apple.com/place?coordinate=35.6586,139.7454&name=Tokyo%20Tower"), 35.6586, 139.7454)
        val address = place("http://maps.apple.com/?address=1%20Infinite%20Loop,%20Cupertino,%20CA")
        assertFalse(address.hasCoordinates)
        assertEquals("1 Infinite Loop, Cupertino, CA", address.name)
    }

    // ---------------------------------------------------------------- Plus Codes and coordinates

    @Test fun full_plus_codes_decode_to_the_centre() {
        val p = place("849VCWC8+R9")
        assertEquals(Service.PLUS_CODE, p.service)
        assertAt(p, 37.4220625, -122.0840625, tolerance = 1e-6)
        assertAt(place("https://plus.codes/849VCWC8+R9"), 37.4220625, -122.0840625, tolerance = 1e-6)
        // Padded codes are whole areas.
        assertAt(place("8FVC0000+"), 47.5, 8.5, tolerance = 1e-6)
    }

    @Test fun short_plus_codes_stay_text() {
        val p = place("CWC8+R9 Mountain View")
        assertEquals(Service.PLUS_CODE, p.service)
        assertFalse(p.hasCoordinates)
        assertEquals("CWC8+R9 Mountain View", p.name)
    }

    @Test fun plain_coordinates() {
        assertAt(place("48.85837, 2.29448"), 48.85837, 2.29448)
        assertAt(place("-33.8568 151.2153"), -33.8568, 151.2153)
        assertAt(place("48.8584° N, 2.2945° E"), 48.8584, 2.2945)
        assertAt(place("33.8568° S, 151.2153° E"), -33.8568, 151.2153)
        assertNull(MapLinks.coordinates("91, 10"))
        assertNull(MapLinks.coordinates("10, 181"))
        assertNull(MapLinks.coordinates("0, 0"))
    }

    @Test fun not_a_place() {
        assertNull(MapLinks.parse(""))
        assertNull(MapLinks.parse("Call me tomorrow"))
        assertNull(MapLinks.parse("+44 20 7946 0958"))
        // An unknown web link is kept as it is, without a position.
        val other = place("https://example.com/where")
        assertEquals(Service.OTHER, other.service)
        assertFalse(other.hasCoordinates)
    }

    // ---------------------------------------------------------------- storage and opening

    @Test fun stored_link_keeps_https_and_turns_the_rest_into_openstreetmap() {
        val google = place("https://maps.app.goo.gl/2G6Wb1cVGpJ7t5Xw8")
        assertEquals("https://maps.app.goo.gl/2G6Wb1cVGpJ7t5Xw8", MapLinks.storedLink(google))
        val geo = place("geo:48.8584,2.2945?q=48.8584,2.2945(Eiffel Tower)")
        val stored = MapLinks.storedLink(geo)
        assertEquals("https://www.openstreetmap.org/?mlat=48.8584&mlon=2.2945#map=17/48.8584/2.2945", stored)
        // And Parley reads its own stored link back to the same spot.
        assertAt(place(stored), 48.8584, 2.2945)
        assertEquals("https://www.openstreetmap.org/?mlat=37.422063&mlon=-122.084063#map=17/37.422063/-122.084063", MapLinks.storedLink(place("849VCWC8+R9")))
    }

    @Test fun geo_uri_for_opening() {
        assertEquals("geo:48.8584,2.2945?q=48.8584%2C2.2945%28Eiffel%20Tower%29", MapLinks.geoUri(48.8584, 2.2945, "Eiffel Tower"))
        assertEquals("geo:48.8584,2.2945?q=48.8584%2C2.2945", MapLinks.geoUri(48.8584, 2.2945, null))
        // Brackets in the label would end it early.
        assertEquals("geo:1.5,2.5?q=1.5%2C2.5%28Caf%C3%A9%20%20Central%29", MapLinks.geoUri(1.5, 2.5, "Café (Central)"))
        assertEquals("geo:0,0?q=10%20Downing%20St%2C%20London", MapLinks.searchUri("10 Downing St, London"))
    }

    // ---------------------------------------------------------------- which link belongs to which address

    @Test fun labels() {
        assertEquals("Map (Home)", MapLinks.label("Home"))
        assertEquals("Map", MapLinks.label(" "))
        assertTrue(MapLinks.isMapLabel("map (work)"))
        assertFalse(MapLinks.isMapLabel("Mapping blog"))
        assertEquals("Work", MapLinks.addressLabelOf("Map (Work)"))
        assertNull(MapLinks.addressLabelOf("Map"))
    }

    @Test fun match_by_label_then_the_one_address_left() {
        val sites = listOf(
            MapLinks.Site(null, "https://example.com"),
            MapLinks.Site("Map (Work)", "https://osm.org/go/0EEQjE--"),
            MapLinks.Site("Map", "geo:1,2"),
        )
        assertEquals(mapOf(1 to 1, 0 to 2), MapLinks.match(listOf("Home", "Work"), sites))
        // A bare "Map" row is never guessed onto one of several addresses.
        assertEquals(emptyMap<Int, Int>(), MapLinks.match(listOf("Home", "Work"), listOf(MapLinks.Site("Map", "geo:1,2"))))
        // An unlabelled link to a map service counts for a single address; an ordinary website doesn't.
        assertEquals(mapOf(0 to 0), MapLinks.match(listOf("Home"), listOf(MapLinks.Site(null, "https://maps.app.goo.gl/x"))))
        assertEquals(emptyMap<Int, Int>(), MapLinks.match(listOf("Home"), listOf(MapLinks.Site(null, "https://example.com"))))
        // "Map (Home)" with the address relabelled since: still the only address.
        assertEquals(mapOf(0 to 0), MapLinks.match(listOf("Other"), listOf(MapLinks.Site("Map (Home)", "geo:1,2"))))
    }
}
