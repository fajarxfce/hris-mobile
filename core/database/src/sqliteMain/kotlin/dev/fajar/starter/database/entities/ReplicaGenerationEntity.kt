package dev.fajar.starter.database.entities

import androidx.room.Entity

@Entity(tableName = "replica_generations", primaryKeys = ["ownerKey", "partition", "generation"])
data class ReplicaGenerationEntity(
    val ownerKey: String,
    val partition: String,
    val generation: String,
    val revision: Long,
    val checkpoint: String,
)
