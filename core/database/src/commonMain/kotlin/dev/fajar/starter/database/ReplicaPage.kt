package dev.fajar.starter.database

import kotlinx.serialization.Serializable

@Serializable
data class ReplicaPage(val checkpoint: ReplicaCheckpoint?, val documents: List<ReplicaDocument>)
