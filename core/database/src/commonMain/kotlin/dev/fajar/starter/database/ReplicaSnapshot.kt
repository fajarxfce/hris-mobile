package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/** One complete, bounded replacement; the caller owns encryption and snapshot policy. */
@Serializable
data class ReplicaSnapshot(
    val generation: String,
    val checkpoint: String,
    val documents: List<ReplicaDocument>,
)
