package com.wkhan.hexis.voice

import android.content.Context

import com.wkhan.hexis.bridge.security.PersistentTokenAuthority
import com.wkhan.hexis.bridge.security.TokenAuthority

/**
 * Process-wide addon state. The consent activity mints a grant token and the bridge service verifies
 * against the same authority. The authority persists to the addon's private storage, so a grant
 * survives the addon process being reclaimed between consent and the first call (an in-memory store
 * lost it, which surfaced as "invalid or missing token").
 */
object VoiceAddon {
    @Volatile private var authority: TokenAuthority? = null

    /** The process-wide persistent token authority (created once, from the app context). */
    fun tokenAuthority(context: Context): TokenAuthority =
        authority ?: synchronized(this) {
            authority ?: PersistentTokenAuthority(context.applicationContext, PREFS).also { authority = it }
        }

    private const val PREFS = "hexis_voice_grants" // unchanged name → existing grants still load
}
