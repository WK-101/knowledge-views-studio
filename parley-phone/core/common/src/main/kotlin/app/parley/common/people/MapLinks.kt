package app.parley.common.people

import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Reads a place (coordinates and/or a name) from a map link, entirely offline: Parley has no internet access, so a
 * link is only ever taken apart, never opened or expanded.
 *
 * Understood: `geo:` URIs (RFC 5870, with Android's `?q=lat,lon(Label)`), Google Maps (`/maps/place/NAME/@lat,lon`,
 * the `!3d…!4d…` pin, `?q=`, `?ll=`, `?query=`, `?destination=`, `?center=`), OpenStreetMap (`?mlat=&mlon=`,
 * `#map=z/lat/lon` and the `osm.org/go/…` short codes, which encode the position), Organic Maps, CoMaps and MAPS.ME
 * (`omaps.app`, `ge0.me`, `comaps.at`, `ge0://`, whose codes also encode the position), OsmAnd
 * (`osmand.net/go?lat=&lon=`, `osmand.net/map?pin=`), Magic Earth, Apple Maps (`?ll=`, `?coordinate=`, `?q=`),
 * Plus Codes (full codes decode to a spot; short ones need a nearby town and stay text) and plain coordinates.
 *
 * Short links (`maps.app.goo.gl`, `goo.gl/maps`) only say where they point when their server is asked, which needs
 * the internet: they come back with [Place.needsNetwork] and are kept as they are.
 */
object MapLinks {
    enum class Service { GEO, GOOGLE, OPENSTREETMAP, ORGANIC_MAPS, OSMAND, MAGIC_EARTH, APPLE, PLUS_CODE, COORDINATES, OTHER }

    data class Place(
        /** The link (or code, or coordinates) as found in the text. */
        val link: String,
        val service: Service,
        val lat: Double? = null,
        val lon: Double? = null,
        /** The place's name or address, when the link (or the text around it) says. */
        val name: String? = null,
        /** A short link only its server can expand: no position can be read from it offline. */
        val needsNetwork: Boolean = false,
    ) {
        val hasCoordinates: Boolean get() = lat != null && lon != null
    }

    private const val MAX_TEXT = 4000
    private const val MAX_NAME = 200

    // A geo: label may hold spaces ("geo:0,0?q=1,2(Eiffel Tower)"), so it runs to its closing bracket.
    private val URL = Regex("""(?i)geo:[^\s<>"'(]+(?:\([^)\n]*\)[^\s<>"']*)?|(?:https?://|ge0://|om://|mapsme://|magicearth://)[^\s<>"]+""")
    private val BARE_HOST = Regex(
        """(?i)\b(?:www\.)?(?:google\.[a-z.]{2,6}/maps|maps\.google\.[a-z.]{2,6}|openstreetmap\.org|osm\.org|omaps\.app|ge0\.me|comaps\.[a-z]{2,4}""" +
            """|osmand\.net|maps\.apple\.com|maps\.app\.goo\.gl|goo\.gl/maps|plus\.codes|magicearth\.com)[^\s<>"']*""",
    )
    private const val OLC_ALPHABET = "23456789CFGHJMPQRVWX"
    private val PLUS_CODE = Regex("""(?i)(?<![0-9A-Z])([23456789C][23456789CFGHJMPQRV](?:[23456789CFGHJMPQRVWX]{6}|[23456789CFGHJMPQRVWX]{4}00|[23456789CFGHJMPQRVWX]{2}0000|0{6})\+(?:[23456789CFGHJMPQRVWX]{2,})?)(?![0-9A-Z])""")
    private val SHORT_PLUS_CODE = Regex("""(?i)(?<![0-9A-Z+])[23456789CFGHJMPQRVWX]{4,6}\+[23456789CFGHJMPQRVWX]{2,}(?![0-9A-Z])""")
    private val DECIMAL_PAIR = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")
    private val HEMISPHERE_PAIR = Regex("""(?i)^\s*(\d{1,2}(?:\.\d+)?)\s*°?\s*([NS])\s*[,;\s]\s*(\d{1,3}(?:\.\d+)?)\s*°?\s*([EW])\s*$""")
    private val GOOGLE_HOST = Regex("""^google\.[a-z.]{2,6}$""")
    private val GOOGLE_PIN = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")
    private val AT_COORDS = Regex("""@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
    private val GEO_Q = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""", RegexOption.DOT_MATCHES_ALL)

    /** The first place in [text] (a shared message, a pasted link), or null when it holds none. */
    fun parse(text: String): Place? {
        val t = text.take(MAX_TEXT).trim()
        if (t.isEmpty()) return null
        val found = URL.find(t) ?: BARE_HOST.find(t)
        if (found != null) {
            val raw = trimTrailing(found.value)
            val url = if (found.value.contains("://") || raw.startsWith("geo:", ignoreCase = true)) raw else "https://$raw"
            val place = parseLink(url) ?: return null
            return if (place.name == null) place.copy(name = nameAround(t, found.range)) else place
        }
        PLUS_CODE.find(t)?.let { m ->
            val (lat, lon) = decodePlusCode(m.groupValues[1]) ?: return@let
            return Place(m.groupValues[1].uppercase(Locale.ROOT), Service.PLUS_CODE, lat, lon, nameAround(t, m.range))
        }
        // "9G8F+6X Zürich" needs the town's position to be placed, which only a geocoder knows: it stays text.
        if (SHORT_PLUS_CODE.containsMatchIn(t)) return Place(t, Service.PLUS_CODE, name = t.take(MAX_NAME))
        coordinates(t)?.let { (lat, lon) -> return Place(t, Service.COORDINATES, lat, lon) }
        return null
    }

    /** Sentence punctuation after a link isn't part of it; a closing bracket is, when the link opened one. */
    private fun trimTrailing(link: String): String {
        var l = link.trimEnd('.', ',', ';', '!', '?', '\'')
        while (l.endsWith(")") && l.count { it == ')' } > l.count { it == '(' }) l = l.dropLast(1).trimEnd('.', ',', ';', '!', '?')
        return l
    }

    /** One link (see the class documentation); null when it isn't a link at all. */
    @Suppress("CyclomaticComplexMethod")
    fun parseLink(url: String): Place? {
        if (url.startsWith("geo:", ignoreCase = true)) return parseGeo(url)
        val u = split(url) ?: return null
        val host = u.host
        return when {
            u.scheme == "ge0" || u.scheme == "mapsme" -> parseGe0(url, u.host, u.path.trim('/'))
            u.scheme == "om" -> parseParams(url, Service.ORGANIC_MAPS, u)
            u.scheme == "magicearth" -> parseParams(url, Service.MAGIC_EARTH, u)
            host == "maps.app.goo.gl" || (host == "goo.gl" && u.path.startsWith("/maps")) || host == "g.co" ->
                Place(url, Service.GOOGLE, needsNetwork = true)
            isGoogle(host, u.path) -> parseGoogle(url, u)
            host == "openstreetmap.org" || host == "osm.org" -> parseOsm(url, u)
            host == "omaps.app" || host == "ge0.me" || host.startsWith("comaps.") -> {
                val parts = u.path.trim('/').split('/', limit = 2)
                val code = parts.firstOrNull().orEmpty()
                if (code.length in 2..10 && code != "map") parseGe0(url, code, parts.getOrNull(1).orEmpty()) else parseParams(url, Service.ORGANIC_MAPS, u)
            }
            host == "osmand.net" -> parseOsmand(url, u)
            host == "magicearth.com" -> parseParams(url, Service.MAGIC_EARTH, u)
            host == "maps.apple.com" || host == "maps.apple" -> parseApple(url, u)
            host == "plus.codes" -> {
                // A "+" in the path is part of the code, not a space.
                val code = decode(u.path.trim('/').replace("+", "%2B"))
                val c = PLUS_CODE.matchEntire(code)?.let { decodePlusCode(it.groupValues[1]) }
                Place(url, Service.PLUS_CODE, c?.first, c?.second, name = if (c == null) code.ifBlank { null } else null)
            }
            u.scheme == "http" || u.scheme == "https" -> parseParams(url, Service.OTHER, u)
            else -> null
        }
    }

    // ---------------------------------------------------------------- per service

    private fun parseGeo(url: String): Place {
        val body = url.substring(4)
        val (path, query) = body.split('?', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val params = queryOf(query)
        val coords = path.substringBefore(';').split(',').let { p ->
            if (p.size >= 2) valid(p[0].trim().toDoubleOrNull(), p[1].trim().toDoubleOrNull()) else null
        }
        val q = params["q"]?.trim().orEmpty()
        GEO_Q.matchEntire(q)?.let { m ->
            val at = valid(m.groupValues[1].toDoubleOrNull(), m.groupValues[2].toDoubleOrNull())
            if (at != null) return Place(url, Service.GEO, at.first, at.second, cleanName(m.groupValues[3]))
        }
        return Place(url, Service.GEO, coords?.first, coords?.second, cleanName(q))
    }

    private fun isGoogle(host: String, path: String): Boolean =
        host.startsWith("maps.google.") || (GOOGLE_HOST.matches(host) && (path == "/maps" || path.startsWith("/maps/") || path.startsWith("/maps?")))

    private fun parseGoogle(url: String, u: Link): Place {
        val p = u.query
        // The pin of the place itself beats the map's centre (@…), which is only where the view was.
        val pin = GOOGLE_PIN.find(u.path + "?" + u.rawQuery)?.let { valid(it.groupValues[1].toDoubleOrNull(), it.groupValues[2].toDoubleOrNull()) }
        val fromParams = listOf("q", "query", "ll", "daddr", "destination", "center", "sll", "viewpoint")
            .firstNotNullOfOrNull { k -> p[k]?.let { coordinates(it.removePrefix("loc:")) } }
        val segments = u.path.split('/').map(::decode)
        val placeSeg = segments.indexOf("place").takeIf { it >= 0 }?.let { segments.getOrNull(it + 1) }
            ?: segments.indexOf("search").takeIf { it >= 0 }?.let { segments.getOrNull(it + 1) }
        val fromPath = placeSeg?.let(::coordinates)
        val at = AT_COORDS.find(u.path)?.let { valid(it.groupValues[1].toDoubleOrNull(), it.groupValues[2].toDoubleOrNull()) }
        val c = pin ?: fromParams ?: fromPath ?: at
        // A dropped pin's "place" is its position in degrees and minutes, not a name.
        val name = placeSeg?.takeIf { it.isNotBlank() && coordinates(it) == null && !it.startsWith("@") && '°' !in it }
            ?: listOf("q", "query", "daddr", "destination").firstNotNullOfOrNull { k -> p[k]?.takeIf { coordinates(it.removePrefix("loc:")) == null } }
        return Place(url, Service.GOOGLE, c?.first, c?.second, cleanName(name))
    }

    private fun parseOsm(url: String, u: Link): Place {
        if (u.path.startsWith("/go/")) {
            val c = decodeOsmShortCode(u.path.removePrefix("/go/").substringBefore('/'))
            return Place(url, Service.OPENSTREETMAP, c?.first, c?.second)
        }
        val p = u.query
        val marker = valid(p["mlat"]?.toDoubleOrNull(), p["mlon"]?.toDoubleOrNull())
            ?: valid(p["lat"]?.toDoubleOrNull(), p["lon"]?.toDoubleOrNull())
        val c = marker ?: mapFragment(u.fragment)
        return Place(url, Service.OPENSTREETMAP, c?.first, c?.second, cleanName(p["query"]))
    }

    private fun parseOsmand(url: String, u: Link): Place {
        val p = u.query
        val c = valid(p["lat"]?.toDoubleOrNull(), p["lon"]?.toDoubleOrNull())
            ?: p["pin"]?.let(::coordinates)
            ?: mapFragment(u.fragment)
        return Place(url, Service.OSMAND, c?.first, c?.second, cleanName(p["name"]))
    }

    private fun parseApple(url: String, u: Link): Place {
        val p = u.query
        val c = listOf("coordinate", "ll", "q", "daddr", "sll", "center").firstNotNullOfOrNull { k -> p[k]?.let(::coordinates) }
        val name = listOf("name", "q", "address", "daddr").firstNotNullOfOrNull { k -> p[k]?.takeIf { coordinates(it) == null } }
        return Place(url, Service.APPLE, c?.first, c?.second, cleanName(name))
    }

    /** Links that say where with plain parameters (`lat`/`lon`, `ll`, `n`, `name`, `q`); Magic Earth, Organic Maps' `om://`, others. */
    private fun parseParams(url: String, service: Service, u: Link): Place {
        val p = u.query
        val c = valid(p["lat"]?.toDoubleOrNull(), (p["lon"] ?: p["lng"] ?: p["long"])?.toDoubleOrNull())
            ?: listOf("ll", "q", "coordinate").firstNotNullOfOrNull { k -> p[k]?.let(::coordinates) }
        val name = listOf("name", "n", "q").firstNotNullOfOrNull { k -> p[k]?.takeIf { coordinates(it) == null } }
        return Place(url, service, c?.first, c?.second, cleanName(name))
    }

    private fun parseGe0(url: String, code: String, rawName: String): Place {
        val c = decodeGe0(code)
        val name = rawName.substringBefore('?').takeIf { it.isNotBlank() }?.let { decode(it.replace('_', ' ')) }
        return Place(url, Service.ORGANIC_MAPS, c?.first, c?.second, cleanName(name))
    }

    /** `#map=17/48.8584/2.2945` (OpenStreetMap, OsmAnd; OsmAnd also writes it without `map=`). */
    private fun mapFragment(fragment: String): Pair<Double, Double>? {
        val f = fragment.substringAfter("map=").substringBefore('&').split('/')
        return if (f.size >= 3) valid(f[1].toDoubleOrNull(), f[2].toDoubleOrNull()) else null
    }

    // ---------------------------------------------------------------- codes that hold a position

    private const val GE0_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val GE0_MAX_BYTES = 10
    private const val GE0_COORD_BITS = 30

    /** Organic Maps / MAPS.ME "ge0" code: a zoom character, then up to 9 characters of interleaved lat/lon bits. */
    internal fun decodeGe0(code: String): Pair<Double, Double>? {
        if (code.length < 2 || code.length > GE0_MAX_BYTES || code.any { GE0_ALPHABET.indexOf(it) < 0 }) return null
        val s = code.substring(1)
        var lat = 0L
        var lon = 0L
        var shift = GE0_COORD_BITS - 3
        for (ch in s) {
            val a = GE0_ALPHABET.indexOf(ch)
            val lat1 = ((a shr 5) and 1 shl 2) or ((a shr 3) and 1 shl 1) or ((a shr 1) and 1)
            val lon1 = ((a shr 4) and 1 shl 2) or ((a shr 2) and 1 shl 1) or (a and 1)
            lat = lat or (lat1.toLong() shl shift)
            lon = lon or (lon1.toLong() shl shift)
            shift -= 3
        }
        // The middle of the square the code narrows down to.
        val middle = 1L shl (3 * (GE0_MAX_BYTES - s.length) - 1)
        lat += middle
        lon += middle
        val max = (1L shl GE0_COORD_BITS) - 1
        return valid(lat.toDouble() / max * 180.0 - 90.0, lon.toDouble() / (max + 1.0) * 360.0 - 180.0)
    }

    private const val OSM_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_~"

    /** OpenStreetMap short link (`osm.org/go/0EEQjE--`): 3 bits of longitude and latitude per character, interleaved. */
    internal fun decodeOsmShortCode(code: String): Pair<Double, Double>? {
        var x = 0L
        var y = 0L
        var z = 0
        // Older links used "@" where newer ones use "~".
        for (c in code.replace('@', '~')) {
            val digit = OSM_ALPHABET.indexOf(c)
            if (digit < 0) {
                if (c == '-') continue else return null
            }
            var t = digit
            repeat(3) {
                x = (x shl 1) or (if (t and 32 != 0) 1L else 0L)
                t = t shl 1
                y = (y shl 1) or (if (t and 32 != 0) 1L else 0L)
                t = t shl 1
            }
            z += 3
        }
        if (z == 0 || z > 32) return null
        x = x shl (32 - z)
        y = y shl (32 - z)
        val lon = x * 90.0 / (1L shl 30) - 180.0
        val lat = y * 45.0 / (1L shl 30) - 90.0
        return valid(lat, lon)
    }

    /** A full Open Location Code ("849VCWC8+R9") to the centre of its area; null for short or broken codes. */
    internal fun decodePlusCode(code: String): Pair<Double, Double>? {
        val c = code.uppercase(Locale.ROOT).replace("+", "").trimEnd('0')
        if (c.length < 2 || c.length % 2 == 1 && c.length < 10) return null
        if (c.any { OLC_ALPHABET.indexOf(it) < 0 }) return null
        var lat = -90.0
        var lon = -180.0
        var res = 20.0
        var size = res
        var i = 0
        while (i < minOf(c.length, 10)) {
            lat += OLC_ALPHABET.indexOf(c[i]) * res
            lon += OLC_ALPHABET.indexOf(c[i + 1]) * res
            size = res
            res /= 20.0
            i += 2
        }
        var latSize = size
        var lonSize = size
        // After ten digits each character picks one of 4 × 5 cells.
        while (i < c.length && i < 15) {
            latSize /= 5.0
            lonSize /= 4.0
            val d = OLC_ALPHABET.indexOf(c[i])
            lat += (d / 4) * latSize
            lon += (d % 4) * lonSize
            i++
        }
        return valid(lat + latSize / 2, lon + lonSize / 2)
    }

    // ---------------------------------------------------------------- coordinates

    /** "48.8584, 2.2945", "48.8584 2.2945" or "48.8584° N, 2.2945° E"; null for anything else or out of range. */
    fun coordinates(text: String): Pair<Double, Double>? {
        val t = text.trim()
        DECIMAL_PAIR.matchEntire(t)?.let { m -> return valid(m.groupValues[1].toDoubleOrNull(), m.groupValues[2].toDoubleOrNull()) }
        HEMISPHERE_PAIR.matchEntire(t)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()?.let { if (m.groupValues[2].equals("S", true)) -it else it }
            val lon = m.groupValues[3].toDoubleOrNull()?.let { if (m.groupValues[4].equals("W", true)) -it else it }
            return valid(lat, lon)
        }
        return null
    }

    /** In range, and not the 0,0 that `geo:0,0?q=…` uses to mean "no position". */
    private fun valid(lat: Double?, lon: Double?): Pair<Double, Double>? {
        if (lat == null || lon == null) return null
        val inRange = lat in -90.0..90.0 && lon in -180.0..180.0
        // (NaN is in no range.)
        if (!inRange || (lat == 0.0 && lon == 0.0)) return null
        return lat to lon
    }

    /** Coordinates as stored and shown: at most 6 decimals (about 10 cm), dot as separator, no trailing zeros. */
    fun format(value: Double): String = BigDecimal(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    fun formatPair(lat: Double, lon: Double): String = format(lat) + ", " + format(lon)

    // ---------------------------------------------------------------- what Parley stores and opens

    /**
     * The link saved with the address: an https link as it was given (every contacts app can open it, and it keeps
     * the service the person chose); otherwise, for a position, an openstreetmap.org link, which works everywhere;
     * otherwise the text itself.
     */
    fun storedLink(p: Place): String {
        val l = p.link.trim()
        if (l.startsWith("https://", true) || l.startsWith("http://", true)) return l
        val lat = p.lat
        val lon = p.lon
        if (lat != null && lon != null) return osmLink(lat, lon)
        return l
    }

    fun osmLink(lat: Double, lon: Double): String =
        "https://www.openstreetmap.org/?mlat=${format(lat)}&mlon=${format(lon)}#map=17/${format(lat)}/${format(lon)}"

    /**
     * A `geo:` URI any map app opens at exactly [lat], [lon], with [label] as the pin's name (Android's
     * `?q=lat,lon(Label)` form, understood by Google Maps, Organic Maps, OsmAnd, CoMaps, Magic Earth…).
     */
    fun geoUri(lat: Double, lon: Double, label: String?): String {
        val at = format(lat) + "," + format(lon)
        val name = label?.replace('(', ' ')?.replace(')', ' ')?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
        return "geo:$at?q=" + encode(at) + (name?.let { encode("($it)") }.orEmpty())
    }

    /** `geo:0,0?q=address`: a search for the address text in the map app. */
    fun searchUri(address: String): String = "geo:0,0?q=" + encode(address.replace('\n', ' ').trim())

    // ---------------------------------------------------------------- the link's place on the contact

    /**
     * The label of the website row that holds an address's map link: "Map" plus the address's own label, so a
     * contact with several addresses knows which link is whose, and other apps show a readable label.
     */
    const val LABEL = "Map"

    fun label(addressLabel: String?): String = addressLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { "$LABEL ($it)" } ?: LABEL

    fun isMapLabel(label: String?): Boolean {
        val l = label?.trim() ?: return false
        return l.equals(LABEL, ignoreCase = true) || (l.startsWith("$LABEL (", ignoreCase = true) && l.endsWith(")"))
    }

    /** "Map (Home)" → "Home"; "Map" → null. */
    fun addressLabelOf(label: String?): String? {
        val l = label?.trim() ?: return null
        if (!isMapLabel(l) || l.length <= LABEL.length + 3) return null
        return l.substring(LABEL.length + 2, l.length - 1).trim().ifEmpty { null }
    }

    /** One website row of the contact: its custom label (null for the standard types) and its link. */
    data class Site(val label: String?, val url: String)

    /**
     * Which website row (value) is the map link of which address (key), by index. A row labelled "Map (Home)" goes
     * to the address labelled "Home"; a row labelled just "Map", or an unlabelled link to a map service, goes to the
     * one address that is left, never to one of several (a guess could open the wrong place).
     */
    fun match(addressLabels: List<String>, sites: List<Site>): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        val candidates = sites.indices.filter { i -> isMapLabel(sites[i].label) || (sites[i].label.isNullOrBlank() && isMapService(sites[i].url)) }
        val left = candidates.toMutableList()
        for (i in candidates) {
            val want = addressLabelOf(sites[i].label)
            val a = want?.let { w -> addressLabels.indices.firstOrNull { it !in out && addressLabels[it].trim().equals(w, ignoreCase = true) } }
            if (a != null) {
                out[a] = i
                left -= i
            }
        }
        val free = addressLabels.indices.filter { it !in out }
        if (free.size == 1) left.firstOrNull()?.let { out[free[0]] = it }
        return out
    }

    /** A link to a known map service that says where (or would, online). */
    private fun isMapService(url: String): Boolean =
        parseLink(url.trim())?.let { it.service != Service.OTHER && (it.hasCoordinates || it.needsNetwork) } == true

    // ---------------------------------------------------------------- helpers

    private class Link(val scheme: String, val host: String, val path: String, val rawQuery: String, val query: Map<String, String>, val fragment: String)

    /** A tolerant split (pasted links often hold spaces, `|` or unencoded letters that java.net.URI refuses). */
    private fun split(url: String): Link? {
        val schemeEnd = url.indexOf(':')
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd).lowercase(Locale.ROOT)
        var rest = url.substring(schemeEnd + 1).removePrefix("//")
        val fragment = rest.substringAfter('#', "")
        rest = rest.substringBefore('#')
        val rawQuery = rest.substringAfter('?', "")
        rest = rest.substringBefore('?')
        val authority = rest.substringBefore('/')
        val path = rest.substring(authority.length)
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT).removePrefix("www.")
        return Link(scheme, host, path, rawQuery, queryOf(rawQuery), fragment)
    }

    private fun queryOf(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in q.split('&')) {
            if (part.isEmpty()) continue
            val k = decode(part.substringBefore('=')).lowercase(Locale.ROOT)
            if (k !in out) out[k] = decode(part.substringAfter('=', ""))
        }
        return out
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s.replace("%(?![0-9a-fA-F]{2})".toRegex(), "%25"), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        s
    }

    private fun encode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

    private fun cleanName(s: String?): String? =
        s?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() && coordinates(it) == null }?.take(MAX_NAME)

    /**
     * The name a share put around its link ("Eiffel Tower\nAv. Gustave Eiffel, Paris\nhttps://…"): the lines before
     * the link, joined, when there are any and they read like a name or address.
     */
    private fun nameAround(text: String, range: IntRange): String? {
        val before = text.substring(0, range.first).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val after = text.substring(range.last + 1).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val lines = before.ifEmpty { after }
        return cleanName(lines.joinToString(", ").takeIf { it.length in 1..MAX_NAME })
    }
}
