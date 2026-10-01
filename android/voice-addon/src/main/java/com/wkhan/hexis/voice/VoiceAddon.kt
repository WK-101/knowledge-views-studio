package com.wkhan.hexis.voice

import com.wkhan.hexis.bridge.security.InMemoryTokenAuthority
import com.wkhan.hexis.bridge.security.TokenAuthority

/**
 * Process-wide addon state. The consent activity mints a grant token here and the bridge service
 * verifies against the same authority.
 *
 * TODO(Phase 1): persist granted tokens (encrypted) so a grant survives process death without a
 * re-consent; the in-memory authority is fine for the skeleton and dev builds.
 */
object VoiceAddon {
    val tokenAuthority: TokenAuthority by lazy { InMemoryTokenAuthority() }
}

/**
 * Who may drive this addon, and how strictly. The real boundary is the pinned signing keyset of the
 * Hexis core; a keyset (not one cert) is pinned so release, debug and F-Droid builds all qualify.
 */
object VoiceAddonSecurity {

    /** SHA-256 signing-certificate digests of the Hexis core builds permitted to bind this addon. */
    val pinnedCoreKeyset: Set<String> = setOf(
        // Hexis release signing certificate.
        "24739ee4974cb6ac2a2a517f47c2876101ae0c6cdc9c4414ce9fabc2dd6e3f96",
        // TODO(Phase 1): add the debug signing cert + the F-Droid reproducible-build cert digests.
    )

    /**
     * Release always enforces signature trust. Debug relaxes it so the addon can be exercised with a
     * debug-signed core before that debug cert is pinned above.
     */
    val requireSignatureTrust: Boolean = !BuildConfig.DEBUG
}
