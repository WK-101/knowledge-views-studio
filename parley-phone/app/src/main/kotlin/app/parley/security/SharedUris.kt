package app.parley.security

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Which URIs another app may hand to Parley to read (a shared vCard or picture, a vCard to view). Parley reads them
 * with its own identity, so only `content://` URIs of other apps' providers are taken: never `file://` (a path into
 * any readable storage, including Parley's own) and never one of Parley's own providers (its exports, private-contact
 * photos), which another app must not be able to make Parley open on its behalf.
 */
object SharedUris {
    fun acceptable(context: Context, uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = uri.authority ?: return false
        if (authority.split(';').any { ownAuthority(context, it) }) return false
        return true
    }

    private fun ownAuthority(context: Context, authority: String): Boolean {
        if (authority.startsWith(context.packageName)) return true
        val owner = runCatching { context.packageManager.resolveContentProvider(authority, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull()
        return owner?.packageName == context.packageName
    }
}
