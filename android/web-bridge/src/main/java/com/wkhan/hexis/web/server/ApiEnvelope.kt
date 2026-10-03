package com.wkhan.hexis.web.server

import kotlinx.serialization.Serializable

/**
 * The decrypted inner request the browser sends (inside the AES-GCM blob). [ts] + [nonce] drive replay
 * protection; [kind] is "query" or "mutate" and maps to the core's `data` capability.
 */
@Serializable
data class ApiRequest(
    val ts: Long,
    val nonce: String,
    val kind: String,
    val domain: String,
    val op: String,
    val cursor: String? = null,
    val limit: Int = 100,
    val paramsJson: String = "{}",
    val payloadJson: String = "{}",
)

/** The decrypted inner response. [dataJson] is the core's DataPage/DataResult JSON passed straight through. */
@Serializable
data class ApiResponse(
    val ok: Boolean,
    val dataJson: String? = null,
    val error: String? = null,
)

/** Answer to a `hello` request: lets the browser learn its own access so it can tailor the UI. */
@Serializable
data class HelloInfo(
    val readOnly: Boolean = false,
    val name: String = "",
)
