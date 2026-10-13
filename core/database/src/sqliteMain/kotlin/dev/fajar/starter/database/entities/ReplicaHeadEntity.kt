package dev.fajar.starter.database.entities

import androidx.room.Entity

@Entity(tableName = "replica_heads", primaryKeys = ["ownerKey", "partition"])
data class ReplicaHeadEntity(
    val ownerKey: String,
    val partition: String,
    val activeGeneration: String?,
    val stagingGeneration: String?,
)
