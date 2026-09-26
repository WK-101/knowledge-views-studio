package app.parley.common.qr

import app.parley.common.record.ContactRecord

/**
 * Q1: what a scanned (or pasted) QR code holds, classified by [QrParser]. Every payload is untrusted: the app shows
 * it in plain words and acts only on the user's tap, never by itself, and never looks anything up on the network.
 * [raw] is the whole text as scanned (capped at [QrParser.MAX_INPUT]).
 */
sealed interface QrPayload {
    val raw: String

    /** One or more contacts: a vCard (2.1/3.0/4.0), MECARD or BIZCARD, read through Parley's vCard engine. */
    data class Contact(
        override val raw: String,
        val format: ContactFormat,
        val records: List<ContactRecord>,
        /** The cards as vCard text (the scanned text for vCards, a vCard 3.0 made from MECARD/BIZCARD). */
        val vcard: String,
    ) : QrPayload

    /** A Parley link: an encrypted contact (`parley://qr`), a simple-mode setup (`parley://simple`), a rule pack (`parley://template`). */
    data class Parley(override val raw: String, val kind: ParleyKind) : QrPayload

    /** A `tel:` number. [isMmi]: it has `*` or `#` (a service or MMI code): shown in full, put on the keypad, never dialled from here. */
    data class Phone(override val raw: String, val number: String, val isMmi: Boolean) : QrPayload

    /** `sms:`, `smsto:`, `mms:`: [numbers] (the first one is used) and an optional [body]. */
    data class Sms(override val raw: String, val numbers: List<String>, val body: String?) : QrPayload {
        val number: String get() = numbers.firstOrNull().orEmpty()
    }

    /** `mailto:` or `MATMSG:` (or a bare address). */
    data class Email(
        override val raw: String,
        val to: List<String>,
        val cc: List<String> = emptyList(),
        val bcc: List<String> = emptyList(),
        val subject: String? = null,
        val body: String? = null,
    ) : QrPayload

    /** `geo:lat,lon[,alt][?q=…]` (RFC 5870). */
    data class Geo(override val raw: String, val lat: Double, val lon: Double, val altitude: Double?, val query: String?) : QrPayload

    /** `WIFI:T:WPA;S:…;P:…;H:true;;`. Parley can't join networks itself; the app offers Android's own screens. */
    data class Wifi(
        override val raw: String,
        val ssid: String,
        val security: WifiSecurity,
        val password: String?,
        val hidden: Boolean,
        /** EAP method (E:), anonymous identity (A:), identity (I:), phase 2 (PH2:) for enterprise networks. */
        val eapMethod: String? = null,
        val identity: String? = null,
        val anonymousIdentity: String? = null,
        val phase2: String? = null,
    ) : QrPayload

    /** A calendar event (`BEGIN:VEVENT`), for the calendar app's "new event" screen. */
    data class Event(
        override val raw: String,
        val summary: String,
        val start: IcsTime?,
        val end: IcsTime?,
        val location: String?,
        val description: String?,
        val url: String?,
    ) : QrPayload

    /** A chat or profile link of a known messenger ([app]); see [MessengerQr]. */
    data class Messenger(
        override val raw: String,
        val app: QrApp,
        val kind: LinkKind,
        /** What the link points to, for display: a username, an ID, an invite code… (null when opaque). */
        val handle: String?,
        /** The number in international form ("+4915…") for links to a phone number. */
        val phone: String?,
        /** The link Parley would hand to the app (the scanned link, normalised). */
        val uri: String,
    ) : QrPayload {
        /** Whether "Open in browser" is possible (https links only; custom schemes have no web page). */
        val hasWebPage: Boolean get() = uri.startsWith("https://")
    }

    /** A web address with what [UrlSafety] noticed about it. */
    data class Url(override val raw: String, val url: String, val info: UrlSafety.Info) : QrPayload

    /** Anything else, shown as text. [truncated]: longer than [QrParser.MAX_INPUT] and cut. */
    data class Text(override val raw: String, val truncated: Boolean = false) : QrPayload
}

enum class ContactFormat { VCARD, MECARD, BIZCARD }

enum class ParleyKind { CONTACT, SIMPLE, TEMPLATE }

enum class WifiSecurity { OPEN, WEP, WPA, SAE, EAP }

/** What a messenger link opens. */
enum class LinkKind { PHONE, PROFILE, ID, GROUP, CHANNEL, INVITE, LINK }

/** A date or date-time from an iCalendar property: [hour] null means a whole day. */
data class IcsTime(
    val year: Int, val month: Int, val day: Int,
    val hour: Int? = null, val minute: Int = 0, val second: Int = 0,
    /** Ends in Z: UTC. */
    val utc: Boolean = false,
    /** TZID parameter, when given. */
    val tzid: String? = null,
) {
    val allDay: Boolean get() = hour == null

    /**
     * Milliseconds since the epoch; floating and whole-day times are read in [zone], TZID wins when it's known.
     * Null for a date or time that doesn't exist (the parser refuses those, but this is untrusted input).
     */
    fun toEpochMillis(zone: java.time.ZoneId): Long? {
        val z = when {
            utc -> java.time.ZoneOffset.UTC
            allDay -> zone
            else -> tzid?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: zone
        }
        return try {
            java.time.LocalDateTime.of(year, month, day, hour ?: 0, minute, second).atZone(z).toInstant().toEpochMilli()
        } catch (_: java.time.DateTimeException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }
}
