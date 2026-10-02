package app.parley.common.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The Parley PIN: an app-lock code of Parley's own, beside the phone's screen lock, and the optional duress PIN that
 * opens Parley with sensitive things hidden (docs/SECURITY_MODEL.md, "Duress unlock"). Only salted scrypt hashes are
 * kept, never the PINs; the app seals the whole record with a Keystore-wrapped key on top.
 */
object PinRules {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 12

    /** Digits only (ASCII after [normalize]), 4 to 12 of them. */
    fun valid(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    /** What a PIN field keeps of typed text: native digits read as ASCII ("١٢٣٤" is "1234"), anything else dropped. */
    fun normalize(typed: String): String = buildString {
        for (ch in typed) {
            val d = Character.digit(ch, 10)
            if (d >= 0) append(('0' + d))
        }
    }.take(MAX_LENGTH)

    /** Why a new duress PIN can't be used, or null: it must be valid and differ from the Parley PIN. */
    fun duressProblem(record: PinRecord, duress: String): PinProblem? = when {
        !valid(duress) -> PinProblem.INVALID
        PinHasher.matches(record, record.pin, duress) -> PinProblem.SAME_AS_PIN
        else -> null
    }
}

enum class PinProblem { INVALID, SAME_AS_PIN }

/** What an entered PIN turned out to be. Both PINs unlock; nothing on screen tells them apart. */
enum class PinVerdict { NORMAL, DURESS, WRONG }

/**
 * The stored record. [salt] is shared by both hashes so one attempt costs one scrypt run, whichever PIN it is (the
 * time an attempt takes says nothing about which matched). [failures] and [lastFailureAt] (elapsed realtime, not the
 * wall clock, which the person holding the phone can change) drive [PinBackoff].
 */
data class PinRecord(
    val salt: String,
    val log2N: Int,
    val r: Int,
    val p: Int,
    val pin: String,
    val duress: String? = null,
    /** On a duress unlock, private contacts' details stay locked (even right after the phone's own unlock). */
    val lockVaultOnDuress: Boolean = true,
    val failures: Int = 0,
    val lastFailureAt: Long = 0L,
) {
    val hasDuress: Boolean get() = duress != null

    fun encode(): String = listOf(
        VERSION, log2N.toString(), r.toString(), p.toString(), salt, pin, duress.orEmpty(),
        if (lockVaultOnDuress) "1" else "0", failures.toString(), lastFailureAt.toString(),
    ).joinToString(SEP)

    companion object {
        private const val VERSION = "p1"
        private const val SEP = ";"

        /** A stored record, or null when it isn't one (damaged, or from a future version). */
        fun decode(s: String?): PinRecord? {
            val f = s?.split(SEP) ?: return null
            if (f.size != 10 || f[0] != VERSION) return null
            return runCatching {
                PinRecord(
                    salt = f[4].also { Base64.getDecoder().decode(it) },
                    log2N = f[1].toInt().also { require(it in 10..20) },
                    r = f[2].toInt().also { require(it in 1..16) },
                    p = f[3].toInt().also { require(it in 1..4) },
                    pin = f[5].also { require(it.isNotEmpty()) },
                    duress = f[6].ifEmpty { null },
                    lockVaultOnDuress = f[7] == "1",
                    failures = f[8].toInt().coerceAtLeast(0),
                    lastFailureAt = f[9].toLong(),
                )
            }.getOrNull()
        }
    }
}

/** scrypt over the PIN with the record's salt. 16 MB per attempt on the phone; tests pass a smaller cost. */
object PinHasher {
    const val LOG2N = 14
    const val R = 8
    const val P = 1
    private const val HASH_BYTES = 32
    private val random = SecureRandom()

    private fun hash(pin: String, salt: String, log2N: Int, r: Int, p: Int): ByteArray =
        Scrypt.derive(pin.toByteArray(Charsets.US_ASCII), Base64.getDecoder().decode(salt), 1 shl log2N, r, p, HASH_BYTES)

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    /** A new record for [pin] with a fresh salt (no duress PIN yet). */
    fun create(pin: String, log2N: Int = LOG2N, r: Int = R, p: Int = P): PinRecord {
        require(PinRules.valid(pin))
        val salt = b64(ByteArray(16).also(random::nextBytes))
        return PinRecord(salt, log2N, r, p, b64(hash(pin, salt, log2N, r, p)))
    }

    /** [record] with the Parley PIN changed; the salt stays, so the duress PIN keeps working. */
    fun withPin(record: PinRecord, pin: String): PinRecord {
        require(PinRules.valid(pin))
        return record.copy(pin = b64(hash(pin, record.salt, record.log2N, record.r, record.p)))
    }

    /** [record] with the duress PIN set ([duress] valid and not the Parley PIN, see [PinRules.duressProblem]) or removed. */
    fun withDuress(record: PinRecord, duress: String?): PinRecord {
        if (duress == null) return record.copy(duress = null)
        require(PinRules.valid(duress))
        return record.copy(duress = b64(hash(duress, record.salt, record.log2N, record.r, record.p)))
    }

    /** Whether [pin] hashes to [stored] (one of the record's hashes). */
    fun matches(record: PinRecord, stored: String, pin: String): Boolean =
        MessageDigest.isEqual(hash(pin, record.salt, record.log2N, record.r, record.p), Base64.getDecoder().decode(stored))

    /**
     * One scrypt run, then both comparisons in constant time, always both: a duress PIN and the Parley PIN take the
     * same time and the same path. A PIN that isn't valid is wrong without hashing (the field can't produce one).
     */
    fun verify(record: PinRecord, pin: String): PinVerdict {
        if (!PinRules.valid(pin)) return PinVerdict.WRONG
        val h = hash(pin, record.salt, record.log2N, record.r, record.p)
        val normal = MessageDigest.isEqual(h, Base64.getDecoder().decode(record.pin))
        val duress = MessageDigest.isEqual(h, Base64.getDecoder().decode(record.duress ?: record.pin)) && record.duress != null
        return when {
            normal -> PinVerdict.NORMAL
            duress -> PinVerdict.DURESS
            else -> PinVerdict.WRONG
        }
    }
}

/**
 * Rate limiting for wrong PINs. Five tries are free; then each wrong one makes the next wait 30 s, doubling up to an
 * hour. A 4-digit PIN then takes more than a year to guess at the screen. The wait counts in elapsed realtime: changing
 * the clock doesn't shorten it, and after a restart (when elapsed time starts again) the wait starts over in full.
 */
object PinBackoff {
    const val FREE_TRIES = 5
    const val FIRST_WAIT_MS = 30_000L
    const val MAX_WAIT_MS = 60 * 60_000L

    /** How long to wait after [failures] wrong PINs in a row before the next try. */
    fun waitAfter(failures: Int): Long {
        if (failures < FREE_TRIES) return 0L
        val steps = (failures - FREE_TRIES).coerceAtMost(20)
        return (FIRST_WAIT_MS shl steps).coerceAtMost(MAX_WAIT_MS)
    }

    /** Milliseconds until the next try is allowed, at elapsed realtime [now]; 0 when it is allowed now. */
    fun remaining(record: PinRecord, now: Long): Long {
        val wait = waitAfter(record.failures)
        if (wait == 0L) return 0L
        // Elapsed time went backwards: the phone restarted. The wait starts over rather than guessing how long it was off.
        if (now < record.lastFailureAt) return wait
        return (record.lastFailureAt + wait - now).coerceAtLeast(0L)
    }

    /** [record] after an attempt at [now]: a wrong one counts; either right one (Parley or duress PIN) clears the count. */
    fun after(record: PinRecord, verdict: PinVerdict, now: Long): PinRecord = when (verdict) {
        PinVerdict.WRONG -> record.copy(failures = record.failures + 1, lastFailureAt = now)
        else -> record.copy(failures = 0, lastFailureAt = 0L)
    }

    /** [record] with a wait that started before a restart counted again from [now] (see [remaining]). */
    fun rebased(record: PinRecord, now: Long): PinRecord =
        if (waitAfter(record.failures) > 0 && now < record.lastFailureAt) record.copy(lastFailureAt = now) else record
}
