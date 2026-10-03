package com.wkhan.hexis.webbridge

import com.wkhan.hexis.App
import com.wkhan.hexis.addon.BridgeRegistry
import com.wkhan.hexis.bridge.BridgeDispatcher
import com.wkhan.hexis.bridge.provider.BridgeProviderService
import com.wkhan.hexis.bridge.security.BridgeTrust

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The CORE as a bridge **provider** of the `data` capability — the role inversion that lets a consumer
 * addon (the web bridge) read/write the core's data without the data or the DB key ever leaving the core.
 *
 * [BridgeProviderService] verifies the caller (UID → package → pinned keyset) before anything runs; this
 * subclass supplies the dispatcher (the `data` handler + the core's persistent token authority) and the
 * keyset. Exported + gated by the core-defined `signature` permission `…permission.BIND_DATA_BRIDGE`, so a
 * mismatched-signature binder is blocked by the OS before our in-code check even runs.
 *
 * The core gains no dangerous permission from this — `INTERNET` lives in the web-bridge addon, which only
 * ever receives the scoped data this provider returns.
 */
class HexisDataProviderService : BridgeProviderService() {

    private val auditScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val repo get() = (application as App).repository
    private val registry by lazy { BridgeRegistry(applicationContext, (application as App).database) }

    override val dispatcher: BridgeDispatcher by lazy {
        BridgeDispatcher(
            handlers = listOf(
                DataCapabilityHandler(
                    source = RepositoryDataSource(repo),
                    audit = { callerPkg, detail, outcome ->
                        auditScope.launch {
                            runCatching { registry.audit(callerPkg, "data", detail, outcome) }
                        }
                    },
                ),
            ),
            tokens = HexisDataAuthority.tokens(applicationContext),
            requireSignatureTrust = BridgeTrust.requireSignatureTrust(applicationContext),
        )
    }

    override val pinnedCallerKeyset: Set<String> = BridgeTrust.HEXIS_KEYSET
}
