package com.wkhan.hexis.addon

import android.content.Context
import android.content.pm.ApplicationInfo

import com.wkhan.hexis.bridge.Capabilities
import com.wkhan.hexis.bridge.client.BridgeDiscovery
import com.wkhan.hexis.bridge.client.DiscoveredProvider
import com.wkhan.hexis.data.AppDatabase
import com.wkhan.hexis.data.entity.BridgeAuditEntity
import com.wkhan.hexis.data.entity.SettingEntity

import java.util.UUID

/**
 * Core-side registry for satellite addons (Phase 0/1).
 *
 * It discovers bridge providers, holds the core's grant state for each (small key-value rows in the
 * existing `settings` table — it owns no schema of its own), records a capped, redaction-aware audit
 * log, and owns the kill switch. The live bind / consent / listen flow is driven from the settings
 * UI; this class is the state and policy behind it.
 *
 * The core holds no dangerous permission and never hands the database key to an addon — it only
 * consumes results. Trust is a pinned signing keyset, relaxed in a debuggable build so a debug-signed
 * addon can be exercised before its debug certificate is pinned.
 */
class BridgeRegistry(
    private val context: Context,
    db: AppDatabase,
) {
    private val settings = db.settingDao()
    private val auditDao = db.bridgeAuditDao()

    /** SHA-256 signing-certificate digests of addon builds the core trusts. */
    private val pinnedKeyset: Set<String> = setOf(
        // Hexis release signing certificate (first-party addons ship signed by the same keyset).
        "24739ee4974cb6ac2a2a517f47c2876101ae0c6cdc9c4414ce9fabc2dd6e3f96",
        // TODO(Phase 1): add the F-Droid reproducible-build certificate digest.
    )

    /** Release enforces the keyset; a debuggable build relaxes it so a debug-signed addon can connect. */
    private val requireTrust: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0

    // ---- Discovery ------------------------------------------------------------------------------

    /** Installed providers advertising [capabilityId]; in a debuggable build, untrusted ones included. */
    fun discover(capabilityId: String): List<DiscoveredProvider> =
        BridgeDiscovery.discover(context, pinnedKeyset)
            .filter { it.supports(capabilityId) && (it.trusted || !requireTrust) }

    fun discoverVoice(): List<DiscoveredProvider> = discover(Capabilities.VOICE_STT)

    // ---- Grant state (key-value rows in the shared settings table) ------------------------------

    suspend fun grantedPackage(capabilityId: String): String? =
        settings.get(grantKey(capabilityId)).nullIfBlank()

    suspend fun grantedToken(capabilityId: String): String? =
        settings.get(tokenKey(capabilityId)).nullIfBlank()

    suspend fun grant(capabilityId: String, providerPackage: String, token: String) {
        settings.put(SettingEntity(grantKey(capabilityId), providerPackage))
        settings.put(SettingEntity(tokenKey(capabilityId), token))
        audit(providerPackage, capabilityId, method = "grant", outcome = "OK")
    }

    suspend fun revoke(capabilityId: String) {
        val pkg = grantedPackage(capabilityId).orEmpty()
        settings.delete(grantKey(capabilityId))
        settings.delete(tokenKey(capabilityId))
        audit(pkg, capabilityId, method = "revoke", outcome = "OK")
    }

    /** Kill switch: drop every addon grant and record it. */
    suspend fun revokeAll() {
        KNOWN_CAPABILITIES.forEach { revoke(it) }
    }

    suspend fun isEnabled(): Boolean = settings.get(ENABLED_KEY) != "false"

    suspend fun setEnabled(enabled: Boolean) {
        settings.put(SettingEntity(ENABLED_KEY, if (enabled) "true" else "false"))
    }

    // ---- Audit ----------------------------------------------------------------------------------

    /** Append a redaction-aware audit entry ([detail] must never carry user content) and cap the log. */
    suspend fun audit(
        providerPackage: String,
        capabilityId: String,
        method: String,
        outcome: String,
        detail: String = "",
    ) {
        auditDao.insert(
            BridgeAuditEntity(
                id = UUID.randomUUID().toString(),
                atMillis = System.currentTimeMillis(),
                providerPackage = providerPackage,
                capabilityId = capabilityId,
                method = method,
                outcome = outcome,
                detail = detail,
            ),
        )
        auditDao.trimTo(AUDIT_CAP)
    }

    fun observeAudit(limit: Int = AUDIT_CAP) = auditDao.observeRecent(limit)

    suspend fun clearAudit() = auditDao.clear()

    private fun grantKey(capabilityId: String) = "bridge.grant.$capabilityId.pkg"
    private fun tokenKey(capabilityId: String) = "bridge.grant.$capabilityId.token"
    private fun String?.nullIfBlank(): String? = if (isNullOrBlank()) null else this

    private companion object {
        const val ENABLED_KEY = "bridge.enabled"
        const val AUDIT_CAP = 200
        val KNOWN_CAPABILITIES = listOf(Capabilities.VOICE_STT)
    }
}
