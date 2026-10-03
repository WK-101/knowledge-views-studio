package com.wkhan.hexis.webbridge

import com.wkhan.hexis.bridge.data.DataMutation
import com.wkhan.hexis.bridge.data.DataPage
import com.wkhan.hexis.bridge.data.DataQuery

/**
 * The curated facade the `data` capability serves — a small, deliberate projection over the repository
 * (never its raw 294-method surface). Kept as an interface so the capability handler is unit-testable with
 * a fake, and so the read/write split is explicit. The real implementation is [RepositoryDataSource].
 */
interface DataSource {
    /** Read. Throws [UnsupportedDomainException] for an unknown domain/op. */
    suspend fun query(query: DataQuery): DataPage

    /** Write (W2+). Returns a failed [DataResult] (not a throw) for a disallowed/unknown op. */
    suspend fun mutate(mutation: DataMutation): com.wkhan.hexis.bridge.data.DataResult
}

/** A requested domain/op is not part of the facade. */
class UnsupportedDomainException(message: String) : Exception(message)
