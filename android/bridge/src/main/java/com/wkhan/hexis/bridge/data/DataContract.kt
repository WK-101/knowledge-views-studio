package com.wkhan.hexis.bridge.data

import kotlinx.serialization.Serializable

/**
 * The `data` capability contract, v1 — how a consumer addon (e.g. the web bridge) reads and writes the
 * core's own data over the bridge, with the CORE as provider.
 *
 * Deliberately **generic** so adding a domain is additive (never a spine change): one query shape and one
 * mutation shape carry a `domain` + `op` that map to a **curated, versioned facade** over the repository —
 * never the raw repository surface. Authorization is per domain + read/write, checked by the core's
 * handler against the caller's granted scope set (see [com.wkhan.hexis.bridge.BridgeScopes]).
 *
 * Payloads are JSON strings so the consumer can forward them to a browser untouched; the typed per-domain
 * shapes (e.g. [TaskDto], [NoteDto]) are the documented contract of what those strings contain.
 */
object DataApi {
    const val CAPABILITY = "data"
    const val CONTRACT_VERSION = 1

    const val METHOD_QUERY = "query"
    const val METHOD_MUTATE = "mutate"

    /** Open a long-lived stream that emits a tick ([EVENT_CHANGED]) whenever a readable domain changes. */
    const val METHOD_CHANGES = "changes"

    /** The stream event kind the core emits on a data change; payload is `{"domain":"tasks"|"notes"|…}`. */
    const val EVENT_CHANGED = "changed"

    // Domains
    const val DOMAIN_TASKS = "tasks"
    const val DOMAIN_NOTES = "notes"

    // Ops (read)
    const val OP_LIST = "list"
    const val OP_GET = "get"
    // Ops (write — W2+)
    const val OP_UPSERT = "upsert"
    const val OP_DELETE = "delete"
    const val OP_COMPLETE = "complete"
}

/** A read request. [params Json] carries op-specific args (e.g. an id, a filter). */
@Serializable
data class DataQuery(
    val domain: String,
    val op: String = DataApi.OP_LIST,
    val cursor: String? = null,
    val limit: Int = 100,
    val paramsJson: String = "{}",
)

/** A page of results: [payloadJson] is a JSON array (or object for `get`) of the domain's DTOs. */
@Serializable
data class DataPage(
    val payloadJson: String,
    val nextCursor: String? = null,
    val total: Int? = null,
)

/** A write request. [payloadJson] is the domain DTO (or op-specific object) to apply. */
@Serializable
data class DataMutation(
    val domain: String,
    val op: String,
    val payloadJson: String = "{}",
)

/** The outcome of a mutation; [payloadJson] optionally carries the resulting DTO (e.g. the new id). */
@Serializable
data class DataResult(
    val ok: Boolean,
    val payloadJson: String? = null,
    val error: String? = null,
)

/**
 * The consent handoff for the `data` capability — the mirror of [com.wkhan.hexis.bridge.BridgeConsent],
 * but hosted by the CORE (which owns the data) and launched by the consumer addon. The addon states who
 * it is and which scopes it wants; on the user's approval the core mints a scoped token and returns it.
 */
object DataConsent {
    const val ACTION = "com.wkhan.hexis.bridge.DATA_CONSENT"
    const val EXTRA_CONSUMER_PACKAGE = "com.wkhan.hexis.bridge.extra.CONSUMER_PACKAGE"
    const val EXTRA_SCOPES = "com.wkhan.hexis.bridge.extra.SCOPES" // comma-separated requested scopes
    const val EXTRA_GRANTED_SCOPES = "com.wkhan.hexis.bridge.extra.GRANTED_SCOPES" // comma-separated, returned
    const val EXTRA_TOKEN = "com.wkhan.hexis.bridge.extra.TOKEN"
}
