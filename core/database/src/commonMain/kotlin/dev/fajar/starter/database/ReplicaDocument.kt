package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/**
 * A null payload is a retained tombstone. Ordering and equality tags are visible to storage;
 * callers must use keyed tokens for sensitive filters and authenticate them with the content.
 */
@Serializable
data class ReplicaDocument(
    val id: String,
    val version: Long,
    val sortKey: String,
    val payload: String?,
    val tags: Set<String> = emptySet(),
)
