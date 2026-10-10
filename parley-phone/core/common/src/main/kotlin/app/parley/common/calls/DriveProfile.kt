package app.parley.common.calls

import app.parley.common.Codecs
import java.util.Locale
import kotlinx.serialization.Serializable

/** A Bluetooth device the user marked as their car: its hardware address and the name it had when marked. */
@Serializable
data class CarDevice(val address: String, val name: String)

/**
 * Settings › Calls › Drive profile. Off until a car is marked. While one of [cars] is connected, and only then:
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
        private val json = Codecs.full

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

    /** Android 14+ without "Nearby devices": the address with all but its last two bytes hidden ("XX:XX:XX:XX:AB:CD"). */
    private const val ANONYMIZED_PREFIX = "XX:XX:XX:XX:"
    private const val ADDRESS_LENGTH = 17
    private const val TAIL_LENGTH = 5

    /**
     * A connected audio device as the call path sees it (a Bluetooth address and product name, either may be missing),
     * and its output [type] (android.media.AudioDeviceInfo's TYPE_*, see [CallAudioOutputs]; null when unknown).
     */
    data class Connected(val address: String?, val name: String?, val type: Int? = null)

    /** [raw] in one form for comparing ("aa:bb:…" and "AA-BB-…" are the same device). */
    fun address(raw: String): String = raw.trim().uppercase(Locale.ROOT).replace('-', ':')

    /** Whether [address] (in [address] form) has only its last two bytes ("XX:XX:XX:XX:AB:CD"). */
    fun anonymized(address: String): Boolean = address.length == ADDRESS_LENGTH && address.startsWith(ANONYMIZED_PREFIX)

    /**
     * Whether [car] is the device at [address] (shown, or with its last two bytes only) called [name]. A full address
     * must match exactly; when either side has only the last two bytes, those must match and so must the name, when
     * the connected device gives one.
     */
    private fun sameDevice(car: CarDevice, address: String, name: String?): Boolean {
        val mine = address(car.address)
        if (!anonymized(mine) && !anonymized(address)) return mine == address
        if (mine.takeLast(TAIL_LENGTH) != address.takeLast(TAIL_LENGTH)) return false
        return name == null || car.name.trim() == name
    }

    /**
     * The marked car among the [connected] devices, or null. By address (by its last two bytes and the name when
     * Android shows only those); by name only when Android hides the connected device's address altogether (then the
     * name the car had when it was marked must match exactly, see [genericName]).
     */
    fun connectedCar(cfg: DriveProfileConfig, connected: List<Connected>): CarDevice? {
        if (!cfg.enabled) return null
        for (d in connected) {
            val a = d.address?.let(::address)?.takeIf { it.isNotEmpty() && it != ANONYMOUS }
            val n = d.name?.trim()?.takeIf { it.isNotEmpty() }
            val hit = if (a != null) {
                cfg.cars.firstOrNull { sameDevice(it, a, n) }
            } else {
                n?.let { cfg.cars.firstOrNull { c -> c.name.trim() == n } }
            }
            if (hit != null) return hit
        }
        return null
    }

    /**
     * The marked car connected through an output that carries calls (hands-free, LE Audio headset, hearing aid), or
     * null. A car connected for media only (A2DP, cars connect it first; or "Phone calls" off for it) would leave an
     * answered call on the phone's earpiece, so the drive profile never answers on it.
     */
    fun connectedCallCar(cfg: DriveProfileConfig, connected: List<Connected>): CarDevice? =
        connectedCar(cfg, connected.filter { d -> d.type?.let(CallAudioOutputs::carriesCalls) == true })

    /**
     * A name many devices share ("Car Multimedia", "MY CAR", "Bluetooth"): matched by name alone it could be a
     * friend's car or headphones, so the drive profile asks for "Nearby devices" to tell the car by its address.
     */
    fun genericName(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == ' ' }.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        if (n.replace(" ", "").length <= SHORT_NAME) return true
        return n in GENERIC_NAMES || n.split(' ').all { it in GENERIC_WORDS }
    }

    private const val SHORT_NAME = 3
    private val GENERIC_WORDS = setOf(
        "my", "car", "cars", "auto", "vehicle", "audio", "media", "multimedia", "stereo", "radio", "kit", "carkit", "handsfree",
        "hands", "free", "bluetooth", "bt", "hfp", "a2dp", "speaker", "headset", "headphones", "device", "system", "music",
    )
    private val GENERIC_NAMES = setOf("car multimedia", "my car", "car kit", "car audio", "car stereo", "hands free")

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
            val n = d.name?.trim()?.takeIf { it.isNotEmpty() }
            // A connected device shown with its last two bytes only is the paired or marked one it matches, not a new row.
            if (anonymized(a) && (seen.values + cfg.cars).any { sameDevice(it, a, n) }) return@forEach
            seen.putIfAbsent(a, CarDevice(a, n ?: a))
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

    /** Do Not Disturb as the announcement sees it: off, Priority only, or alarms only / total silence. */
    enum class Dnd { OFF, PRIORITY, SILENT }

    /**
     * Whether the phone rings aloud for this caller: the ringer is on, and Do Not Disturb is off, or set to Priority
     * and lets this caller through ([callerAllowed], e.g. a starred contact): the call the driver most wants to hear about.
     */
    fun ringsAloud(ringerNormal: Boolean, dnd: Dnd, callerAllowed: Boolean): Boolean = ringerNormal && when (dnd) {
        Dnd.OFF -> true
        Dnd.PRIORITY -> callerAllowed
        Dnd.SILENT -> false
    }

    /** NotificationManager.Policy's PRIORITY_SENDERS_ANY / _CONTACTS / _STARRED (stable platform values). */
    const val SENDERS_ANY = 0
    const val SENDERS_CONTACTS = 1
    const val SENDERS_STARRED = 2

    /**
     * Before Android 13 (no `matchesCallFilter(Uri)`): whether Priority lets the call through, from its policy: calls
     * allowed at all ([callsAllowed]), and from anyone, any contact ([known]) or starred contacts ([starred]).
     */
    fun priorityAllows(callsAllowed: Boolean, senders: Int, known: Boolean, starred: Boolean): Boolean = callsAllowed && when (senders) {
        SENDERS_ANY -> true
        SENDERS_CONTACTS -> known
        SENDERS_STARRED -> starred
        else -> false
    }

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
