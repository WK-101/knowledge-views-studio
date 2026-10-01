package com.wkhan.hexis.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * R110 (addon bridge) — one audit entry per bridge call the core makes to, or receives from, a
 * satellite addon. Redaction-aware: [detail] holds a short, non-sensitive summary (e.g. a capability
 * method and outcome), never transcript text, note bodies or other user content. The core caps the
 * log size; it rides the encrypted database like every other table.
 */
@Serializable
@Entity(tableName = "bridge_audit_log")
data class BridgeAuditEntity(
    @PrimaryKey val id: String,
    val atMillis: Long,
    val providerPackage: String,
    val capabilityId: String,
    val method: String,
    val outcome: String,
    val detail: String = "",
)
