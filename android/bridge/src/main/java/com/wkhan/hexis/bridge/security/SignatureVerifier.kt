package com.wkhan.hexis.bridge.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

import java.security.MessageDigest

/**
 * Verifies the identity of a bridge peer by pinning its signing certificate(s) against a keyset.
 *
 * The real security boundary is: verified Binder UID -> owning package -> pinned signing keyset.
 * A keyset (not a single certificate) is pinned so the Play, F-Droid and dev builds of a
 * first-party peer are all accepted, while an impostor built and self-signed by someone else is
 * rejected. This is defense the reference transcriber (Scrib) omits — its service trusts any caller.
 */
object SignatureVerifier {

    /**
     * SHA-256 digests (lowercase hex, no separators) of every signing certificate currently held by
     * [packageName]. Empty if the package is absent or unsigned.
     */
    fun signingSha256(context: Context, packageName: String): Set<String> {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signers = info.signingInfo ?: return emptySet()
                val certs = if (signers.hasMultipleSigners()) {
                    signers.apkContentsSigners
                } else {
                    signers.signingCertificateHistory
                }
                certs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
            } else {
                @Suppress("DEPRECATION", "PackageManagerGetSignatures")
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
            }
        } catch (_: PackageManager.NameNotFoundException) {
            emptySet()
        }
    }

    /** True iff [packageName] is signed by at least one certificate in [pinnedKeyset]. */
    fun isTrusted(context: Context, packageName: String, pinnedKeyset: Set<String>): Boolean {
        if (pinnedKeyset.isEmpty()) return false
        val actual = signingSha256(context, packageName)
        return actual.isNotEmpty() && actual.any { it in pinnedKeyset }
    }

    /**
     * The package that owns [callingUid]. Returns null when the UID maps to no package; if a UID is
     * shared by several packages (a shared user id — which first-party addons must not use), the
     * first is returned and callers should treat a shared UID as untrusted.
     */
    fun packageForUid(context: Context, callingUid: Int): String? =
        context.packageManager.getPackagesForUid(callingUid)?.firstOrNull()

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
