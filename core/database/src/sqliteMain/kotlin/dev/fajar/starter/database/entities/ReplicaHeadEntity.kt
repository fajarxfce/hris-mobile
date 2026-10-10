package dev.fajar.starter.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "replica_heads")
data class ReplicaHeadEntity(
    @PrimaryKey val partition: String,
    val activeGeneration: String?,
    val stagingGeneration: String?,
)
