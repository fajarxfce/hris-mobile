package dev.fajar.starter.database.entities

import androidx.room.Entity

@Entity(tableName = "replica_generations", primaryKeys = ["partition", "generation"])
data class ReplicaGenerationEntity(
    val partition: String,
    val generation: String,
    val sessionId: String,
    val revision: Long,
    val checkpoint: String,
)
