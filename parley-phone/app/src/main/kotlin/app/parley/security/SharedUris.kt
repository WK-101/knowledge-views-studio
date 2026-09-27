package app.parley.security

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import app.parley.common.security.SharedUriPolicy
import java.util.Locale

/**
 * Which URIs another app may hand to Parley to read (a shared vCard or picture, a vCard to view). Parley reads them
 * with its own identity, so only `content://` URIs of other apps' providers are taken: never `file://` (a path into
 * any readable storage, including Parley's own), never one with user information in its authority, and never one of
 * Parley's own providers (its exports, private-contact photos), which another app must not be able to make Parley
 * open on its behalf ([SharedUriPolicy]).
 */
object SharedUris {
    fun acceptable(context: Context, uri: Uri): Boolean =
        SharedUriPolicy.acceptable(uri.scheme, uri.authority, uri.encodedAuthority) { ownAuthority(context, it) }

    private fun ownAuthority(context: Context, authority: String): Boolean {
        if (authority.startsWith(context.packageName.lowercase(Locale.ROOT))) return true
        val owner = runCatching { context.packageManager.resolveContentProvider(authority, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull()
        return owner?.packageName == context.packageName
    }
}
