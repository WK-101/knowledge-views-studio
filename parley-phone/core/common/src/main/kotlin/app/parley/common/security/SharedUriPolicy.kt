package app.parley.common.security

import java.util.Locale

/**
 * Whether a URI another app hands to Parley may be read (see the app's SharedUris). Only `content://` URIs of other
 * apps' providers qualify. An authority with user information (`0@provider`, or `%40` encoded) is never taken: the
 * system drops the user part when it resolves the provider, so "0@" + one of Parley's own authorities would otherwise
 * slip past the comparison below and open Parley's own private provider.
 */
object SharedUriPolicy {
    /**
     * [authority] and [encodedAuthority] are the URI's authority as decoded and as sent; [own] says whether a bare
     * authority (lowercase, no user part or port) belongs to one of Parley's own providers.
     */
    fun acceptable(scheme: String?, authority: String?, encodedAuthority: String?, own: (String) -> Boolean): Boolean {
        if (scheme != "content") return false
        if (authority.isNullOrEmpty()) return false
        for (a in listOfNotNull(authority, encodedAuthority)) {
            // User info, an encoded character or a port: none belongs in a provider authority.
            if (a.any { it in FORBIDDEN || it.isWhitespace() }) return false
        }
        // A provider may declare several authorities separated by ';': none of them may be Parley's.
        return authority.split(';').none { it.isEmpty() || own(it.lowercase(Locale.ROOT)) }
    }

    private const val FORBIDDEN = "@%:"
}
