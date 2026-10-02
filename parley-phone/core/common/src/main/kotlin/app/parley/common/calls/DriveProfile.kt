package app.parley.common.calls

import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A Bluetooth device the user marked as their car: its hardware address and the name it had when marked. */
@Serializable
data class CarDevice(val address: String, val name: String)

/**
 * Settings › Calls › Drive profile (I11). Off until a car is marked. While one of [cars] is connected, and only then:
 * the caller's name is said once through the car, favourites and/or chosen people are answered after a few seconds,
 * unknown callers can ring silently, and the reply sheet offers "Driving" messages first. No location is used: the
 * car's Bluetooth connection is the only signal.
 */
@Serializable
data class DriveProfileConfig(
    val cars: List<CarDevice> = emptyList(),
    /** Say "Ana is calling" once, for contacts and private contacts (never a private one in discreet mode). */
    val announce: Boolean = true,
    /** Answer favourites automatically after [answerSeconds]. */
    val answerFavourites: Boolean = false,
    /** Answer the people and labels chosen for auto-answer on their pages. */
    val answerChosen: Boolean = false,
    val answerSeconds: Int = AutoAnswer.DEFAULT_SECONDS,
    /** Numbers that are neither contacts nor private contacts ring silently (they still show as missed calls). */
    val silenceUnknown: Boolean = false,
) {
    val enabled: Boolean get() = cars.isNotEmpty()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(text: String?): DriveProfileConfig = if (text.isNullOrBlank()) {
            DriveProfileConfig()
        } else {
            try {
                json.decodeFromString(serializer(), text).let { c ->
                    c.copy(
                        cars = c.cars.filter { it.address.isNotBlank() }.distinctBy { DriveProfile.address(it.address) },
                        answerSeconds = AutoAnswer.normalise(c.answerSeconds),
                    )
                }
            } catch (_: Exception) {
                DriveProfileConfig()
            }
        }

        fun encode(c: DriveProfileConfig): String = json.encodeToString(serializer(), c)
    }
}

object DriveProfile {
    /** What Android reports for a Bluetooth address it won't tell the app; never matched. */
    private const val ANONYMOUS = "02:00:00:00:00:00"

    /** A connected audio device as the call path sees it (a Bluetooth address and product name, either may be missing). */
    data class Connected(val address: String?, val name: String?)

    /** [raw] in one form for comparing ("aa:bb:…" and "AA-BB-…" are the same device). */
    fun address(raw: String): String = raw.trim().uppercase(Locale.ROOT).replace('-', ':')

    /**
     * The marked car among the [connected] devices, or null. By address; by name only when Android hides the
     * connected device's address (then the name the car had when it was marked must match exactly).
     */
    fun connectedCar(cfg: DriveProfileConfig, connected: List<Connected>): CarDevice? {
        if (!cfg.enabled) return null
        for (d in connected) {
            val a = d.address?.let(::address)?.takeIf { it.isNotEmpty() && it != ANONYMOUS }
            val hit = if (a != null) {
                cfg.cars.firstOrNull { address(it.address) == a }
            } else {
                d.name?.trim()?.takeIf { it.isNotEmpty() }?.let { n -> cfg.cars.firstOrNull { it.name.trim() == n } }
            }
            if (hit != null) return hit
        }
        return null
    }

    /** Marks or unmarks [car]; a car is kept once, by address. */
    fun mark(cfg: DriveProfileConfig, car: CarDevice, on: Boolean): DriveProfileConfig {
        val rest = cfg.cars.filterNot { address(it.address) == address(car.address) }
        return cfg.copy(cars = if (on) rest + car else rest)
    }

    /** One row of the drive profile's device list. */
    data class DeviceRow(val device: CarDevice, val marked: Boolean, val connected: Boolean, val paired: Boolean)

    /**
     * The devices to choose the car from: the [paired] ones (Android 12+ with "Nearby devices"), the Bluetooth audio
     * devices [connected] now (all Android 10 and 11 can list without another permission), and cars marked earlier
     * that are neither (so they can be unmarked). Marked cars first, then by name.
     */
    fun devices(cfg: DriveProfileConfig, paired: List<CarDevice>, connected: List<Connected>): List<DeviceRow> {
        val seen = LinkedHashMap<String, CarDevice>()
        paired.filter { it.address.isNotBlank() }.forEach { seen.putIfAbsent(address(it.address), it) }
        connected.forEach { d ->
            val a = d.address?.let(::address)?.takeIf { it.isNotEmpty() && it != ANONYMOUS } ?: return@forEach
            seen.putIfAbsent(a, CarDevice(a, d.name?.trim()?.takeIf { it.isNotEmpty() } ?: a))
        }
        val pairedKeys = paired.map { address(it.address) }.toSet()
        cfg.cars.forEach { seen.putIfAbsent(address(it.address), it) }
        return seen.map { (a, d) ->
            val one = DriveProfileConfig(cars = listOf(d))
            DeviceRow(d, isMarked(cfg, a), connectedCar(one, connected) != null, a in pairedKeys)
        }.sortedWith(compareBy<DeviceRow>({ !it.marked }, { it.device.name.lowercase(Locale.ROOT) }))
    }

    fun isMarked(cfg: DriveProfileConfig, address: String): Boolean = cfg.cars.any { address(it.address) == address(address) }

    /** The auto-answer scope while [driving], or null when the drive profile answers nobody. */
    fun answerScope(cfg: DriveProfileConfig, driving: Boolean): AutoAnswer.DriveScope? {
        if (!driving || !(cfg.answerFavourites || cfg.answerChosen)) return null
        return AutoAnswer.DriveScope(AutoAnswer.normalise(cfg.answerSeconds), cfg.answerFavourites, cfg.answerChosen)
    }

    /** What the call path knows about a ringing call when deciding to announce or silence it. */
    data class Caller(
        /** The lookup found a contact, or a private contact that may be shown (not in discreet mode). */
        val known: Boolean,
        /**
         * A contact or a private contact, discreet mode or not. Such a caller is never silenced as "unknown", though a
         * hidden private contact's name is never said.
         */
        val saved: Boolean = known,
        val hidden: Boolean = false,
        /** Screening blocked, silenced or flagged it. */
        val blockedOrSpam: Boolean = false,
        /** Another call is going on (the car is busy with it). */
        val otherCall: Boolean = false,
        /** An emergency call or call-back, or the emergency window is open ([EmergencyPolicy]). */
        val emergency: Boolean = false,
        /** Screening let it ring for a reason of its own (a repeat caller, "Expecting a call", an allow rule). */
        val rangThrough: Boolean = false,
        /** The user silenced it (a key, Silence) or the phone is set not to ring (silent, Do Not Disturb). */
        val quiet: Boolean = false,
    )

    /** Say the caller's name once through the car: a known, visible caller on a phone that rings. */
    fun announces(cfg: DriveProfileConfig, driving: Boolean, c: Caller): Boolean =
        driving && cfg.announce && c.known && !c.hidden && !c.blockedOrSpam && !c.otherCall && !c.emergency && !c.quiet

    /**
     * Let an unknown caller ring silently while driving. Never a saved caller (private contacts in discreet mode
     * included), never an emergency call-back, and never a call screening let through on purpose.
     */
    fun silences(cfg: DriveProfileConfig, driving: Boolean, c: Caller): Boolean =
        driving && cfg.silenceUnknown && !c.saved && !c.emergency && !c.rangThrough
}
