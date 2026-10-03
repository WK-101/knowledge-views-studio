package com.wkhan.hexis.web.bridge

import android.content.Context

/**
 * Persists the scoped grant token the CORE minted for this addon (returned from the core's data-consent
 * screen), plus the granted scopes for display. The token authorizes every `data` call; clearing it (or
 * the core's kill switch) locks the web client out.
 */
class GrantStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) { prefs.edit().apply { if (value == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, value) }.apply() }

    var scopes: String
        get() = prefs.getString(KEY_SCOPES, "") ?: ""
        set(value) { prefs.edit().putString(KEY_SCOPES, value).apply() }

    val isConnected: Boolean get() = !token.isNullOrEmpty()

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_SCOPES).apply()
    }

    private companion object {
        const val PREFS = "hexis_web_grant"
        const val KEY_TOKEN = "token"
        const val KEY_SCOPES = "scopes"
    }
}
