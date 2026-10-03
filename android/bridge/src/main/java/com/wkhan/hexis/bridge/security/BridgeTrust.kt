package com.wkhan.hexis.bridge.security

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Single source of truth for which signing identities the Hexis bridge trusts, and when that trust may
 * be relaxed. Both the core (which pins the addon's keyset) and every addon (which pins the core's
 * keyset) read from here, so the first-party keyset and the debug-relaxation policy are defined once
 * rather than copied into each module (where the copies drift when a signing identity is added).
 */
object BridgeTrust {

    /**
     * SHA-256 signing-certificate digests (lowercase hex) of the first-party Hexis keyset. The core and
     * its addons ship signed by the same keyset, so each side pins this to recognize the other.
     */
    val HEXIS_KEYSET: Set<String> = setOf(
        // Hexis release signing certificate.
        "24739ee4974cb6ac2a2a517f47c2876101ae0c6cdc9c4414ce9fabc2dd6e3f96",
        // TODO: add the F-Droid reproducible-build certificate digest once published.
    )

    /**
     * Release always enforces the keyset; a debuggable build relaxes it so a debug-signed peer can be
     * exercised before its debug certificate is pinned. Keyed off the running app's own debuggable flag
     * so the core and the addon make the identical decision from one implementation.
     */
    fun requireSignatureTrust(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0
}
