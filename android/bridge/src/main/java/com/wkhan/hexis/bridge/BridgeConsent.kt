package com.wkhan.hexis.bridge

/**
 * The consent handoff contract. The core launches a provider's consent activity by [ACTION] (with
 * setPackage to pin it to that addon), passing its own package in [EXTRA_CORE_PACKAGE] and the
 * capability in [EXTRA_CAPABILITY]; on approval the activity returns a scoped grant token in
 * [EXTRA_TOKEN]. The addon renders its own permission prompt — the core never holds the permission.
 */
object BridgeConsent {
    const val ACTION = "com.wkhan.hexis.bridge.CONSENT"
    const val EXTRA_CORE_PACKAGE = "com.wkhan.hexis.bridge.extra.CORE_PACKAGE"
    const val EXTRA_CAPABILITY = "com.wkhan.hexis.bridge.extra.CAPABILITY"
    const val EXTRA_TOKEN = "com.wkhan.hexis.bridge.extra.TOKEN"
}
