package com.wkhan.hexis.webbridge

import com.wkhan.hexis.bridge.data.DataMutation
import com.wkhan.hexis.bridge.data.DataPage
import com.wkhan.hexis.bridge.data.DataQuery

import kotlinx.coroutines.flow.Flow

/**
 * The curated facade the `data` capability serves — a small, deliberate projection over the repository
 * (never its raw 294-method surface). Kept as an interface so the capability handler is unit-testable with
 * a fake, and so the read/write split is explicit. The real implementation is [RepositoryDataSource].
 */
interface DataSource {
    /** Read. Throws [UnsupportedDomainException] for an unknown domain/op. */
    suspend fun query(query: DataQuery): DataPage

    /** Write. Returns a failed [DataResult] (not a throw) for a disallowed/unknown op. */
    suspend fun mutate(mutation: DataMutation): com.wkhan.hexis.bridge.data.DataResult

    /**
     * A cold flow that emits the name of a changed domain (`"tasks"` / `"notes"`) whenever that domain's
     * data changes — the signal the `changes` stream turns into live-refresh ticks. Does not emit the
     * initial state (subscribers already loaded it); only subsequent changes.
     */
    fun changes(): Flow<String>
}

/** A requested domain/op is not part of the facade. */
class UnsupportedDomainException(message: String) : Exception(message)
