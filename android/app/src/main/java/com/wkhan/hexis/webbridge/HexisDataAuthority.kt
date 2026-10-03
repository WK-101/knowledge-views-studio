package com.wkhan.hexis.webbridge

import android.content.Context

import com.wkhan.hexis.bridge.security.PersistentTokenAuthority
import com.wkhan.hexis.bridge.security.TokenAuthority

/**
 * Process-wide token authority for the CORE acting as the `data` provider. The core consent screen mints a
 * scoped token for a consumer addon (the web bridge); the core's provider service verifies it on every
 * call. Persisted so a grant survives the core process being reclaimed. Its own prefs file, separate from
 * the voice addon's store.
 */
object HexisDataAuthority {

    private const val PREFS = "hexis_data_grants"

    @Volatile private var authority: TokenAuthority? = null

    fun tokens(context: Context): TokenAuthority =
        authority ?: synchronized(this) {
            authority ?: PersistentTokenAuthority(context.applicationContext, PREFS).also { authority = it }
        }

    /** Kill switch: revoke every grant this core has issued to [consumerPackage]. */
    fun revokeAll(context: Context, consumerPackage: String) {
        tokens(context).revokeAll(consumerPackage)
    }
}
