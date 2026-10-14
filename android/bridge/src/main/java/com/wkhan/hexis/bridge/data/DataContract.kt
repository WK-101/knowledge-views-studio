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
    const val DOMAIN_CALENDAR = "calendar"
    const val DOMAIN_TIME = "time"
    const val DOMAIN_HABITS = "habits"

    // Ops (read)
    const val OP_LIST = "list"
    const val OP_GET = "get"

    /** List the task lists (names) for the sidebar (domain `tasks`, no params) → [ListDto][]. */
    const val OP_LISTS = "lists"

    /** List the notebooks (names) for the notes sidebar (domain `notes`, no params) → [ListDto][]. */
    const val OP_NOTEBOOKS = "notebooks"

    /** List the time activities (for the start-timer picker + report legend); domain `time` → [TimeActivityDto][]. */
    const val OP_ACTIVITIES = "activities"

    /**
     * Per-habit per-day check-in cells for the heatmap (domain `habits`, params `{ "days": N }`,
     * default 84) → [HabitDayDto][] for the active-workspace habits over the last N days.
     */
    const val OP_HISTORY = "history"

    /** List the event calendars (for the web calendar picker + colour legend); domain `calendar` → [CalendarDto][]. */
    const val OP_CALENDARS = "calendars"

    /**
     * Expand recurring events into concrete occurrences inside a window (domain `calendar`,
     * params `{ "from": epochMillis, "to": epochMillis }`) → [EventDto][] whose `startMillis`/`endMillis`
     * are the realised instance times and whose `id` is the owning (master) event — so a web edit/delete of
     * an occurrence maps back to the series. This is how month/week/agenda views get the right dots.
     */
    const val OP_OCCURRENCES = "occurrences"

    /** List a note's attachment metadata (params `{ "id": noteId }`) → [AttachmentDto][]. */
    const val OP_ATTACHMENTS = "attachments"

    /**
     * Fetch one attachment's bytes inline (params `{ "id": attachmentId, "noteId": noteId }`) → [AttachmentData].
     * Only small images are inlined (Base64); larger ones return `tooLarge = true` and are viewed on-device.
     * Binary stays off the fixed spine by design — no `ParcelFileDescriptor`, no oneway-buffer blob streaming.
     */
    const val OP_ATTACHMENT_DATA = "attachmentData"

    /** Max raw bytes inlined over the bridge for an image (keeps a response well under the Binder limit). */
    const val INLINE_ATTACHMENT_MAX_BYTES = 512 * 1024
    // Ops (write — W2+)
    const val OP_UPSERT = "upsert"
    const val OP_DELETE = "delete"
    const val OP_COMPLETE = "complete"

    /** Start a timer for an activity (domain `time`, payload `{ "activityId": id }`); stops any running one first. */
    const val OP_START = "start"

    /** Stop the running timer (domain `time`, payload `{ "id": entryId }` for a specific one, else `{}` for all). */
    const val OP_STOP = "stop"

    /** Tap a habit's today check-in (domain `habits`, payload `{ "id": habitId }`); cycles count up to target, then clears. */
    const val OP_CHECKIN = "checkin"

    /**
     * Bulk write over many ids (W3), for triage on a wide screen. Payload (tasks):
     * `{ "ids": [...], "action": "complete"|"delete"|"star", "value": true|false }`.
     */
    const val OP_BULK = "bulk"
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
