package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/** All pages and the published checkpoint come from one storage transaction. */
@Serializable
data class ReplicaQueryPage(
    val checkpoint: ReplicaCheckpoint?,
    val pages: List<List<ReplicaDocument>>,
)
