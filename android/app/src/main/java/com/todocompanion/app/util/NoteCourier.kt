package com.todocompanion.app.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wave 3 · Encrypted Note Courier — hand one note to a person end-to-end, with no server. The note is
 * serialized to a small JSON payload and wrapped in a passphrase-encrypted [PortableCrypto] envelope
 * (AES-GCM + PBKDF2-HMAC-SHA256, 210k iterations). Send the resulting file over any channel you already
 * trust — Signal, email, a USB stick — and the recipient (or your own other device) opens it with the
 * shared passphrase. Kairo never touches a network to do it; every "private" competitor still routes
 * sharing through their own servers. Pure: the seal/open round-trip is unit-testable without Android.
 */
object NoteCourier {
    const val FILE_EXT = "kairo-note"

    @Serializable
    data class Payload(
        val v: Int = 1,
        val title: String,
        val body: String,
        val kind: String = "note",
        val emoji: String? = null,
        val colorArgb: Long? = null,
        val tags: List<String> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Encrypt a note payload under [pass]; returns the portable envelope string to write to a file. */
    fun seal(payload: Payload, pass: CharArray): String =
        PortableCrypto.encrypt(json.encodeToString(Payload.serializer(), payload), pass)

    /** Decrypt + parse a courier envelope. Returns null on wrong passphrase, tamper, or a non-note blob. */
    fun open(blob: String, pass: CharArray): Payload? {
        val plain = PortableCrypto.decrypt(blob, pass) ?: return null
        return runCatching { json.decodeFromString(Payload.serializer(), plain) }.getOrNull()
    }

    /** True if [blob] is one of our encrypted envelopes (used to route the import path). */
    fun looksEncrypted(blob: String): Boolean = PortableCrypto.isEnvelope(blob)
}
