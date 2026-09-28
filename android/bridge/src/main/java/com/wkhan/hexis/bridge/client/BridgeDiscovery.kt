package com.wkhan.hexis.bridge.client

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

import com.wkhan.hexis.bridge.BridgeProtocol
import com.wkhan.hexis.bridge.security.SignatureVerifier

/** A bridge provider found on the device. [trusted] is true only if its signing keyset matches the pin. */
data class DiscoveredProvider(
    val packageName: String,
    val className: String,
    val capabilities: List<String>,
    val protocolVersion: Int,
    val trusted: Boolean,
) {
    fun supports(capabilityId: String): Boolean = capabilityId in capabilities
}

/**
 * Discovers installed bridge providers via queryIntentServices on [BridgeProtocol.PROVIDER_ACTION].
 * Callers must present a chooser and never auto-bind — binding a provider grants a satellite access.
 */
object BridgeDiscovery {

    fun discover(context: Context, pinnedKeyset: Set<String>): List<DiscoveredProvider> {
        val pm = context.packageManager
        val intent = Intent(BridgeProtocol.PROVIDER_ACTION)
        val resolves = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }
        return resolves.mapNotNull { ri ->
            val svc = ri.serviceInfo ?: return@mapNotNull null
            val meta = svc.metaData
            val caps = meta?.getString(BridgeProtocol.META_CAPABILITIES)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            // android:value may arrive typed as a String literal ("1") or as an Int (@integer/...);
            // accept both without the deprecated Bundle.get(). A string wins if it parses, else the
            // int-typed value (or 0 when absent).
            val version = meta?.getString(BridgeProtocol.META_PROTOCOL_VERSION)?.toIntOrNull()
                ?: meta?.getInt(BridgeProtocol.META_PROTOCOL_VERSION, 0)
                ?: 0
            DiscoveredProvider(
                packageName = svc.packageName,
                className = svc.name,
                capabilities = caps,
                protocolVersion = version,
                trusted = SignatureVerifier.isTrusted(context, svc.packageName, pinnedKeyset),
            )
        }
    }

    /** Trusted providers that advertise [capabilityId] — the candidate set for a chooser / the router. */
    fun providersFor(context: Context, capabilityId: String, pinnedKeyset: Set<String>): List<DiscoveredProvider> =
        discover(context, pinnedKeyset).filter { it.trusted && it.supports(capabilityId) }
}
