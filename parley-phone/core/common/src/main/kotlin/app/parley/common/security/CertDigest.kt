package app.parley.common.security

import java.util.Locale

/**
 * A signing certificate's SHA-256 as people compare it: upper-case byte pairs joined by colons, the way Android's
 * `apksigner` and app stores print it ("AB:CD:…"). Anything that isn't 64 hex digits is shown as it is.
 */
object CertDigest {
    fun shown(hex: String): String {
        val clean = hex.trim().lowercase(Locale.ROOT)
        if (clean.length != 64 || clean.any { it !in '0'..'9' && it !in 'a'..'f' }) return hex
        return clean.uppercase(Locale.ROOT).chunked(2).joinToString(":")
    }
}
