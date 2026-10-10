package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/**
 * A null payload is a retained tombstone; sortKey contains only non-sensitive ordering metadata.
 */
@Serializable
data class ReplicaDocument(
    val id: String,
    val version: Long,
    val sortKey: String,
    val payload: String?,
)
