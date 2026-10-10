package dev.fajar.starter.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "replica_documents",
    primaryKeys = ["partition", "generation", "id"],
    foreignKeys =
        [
            ForeignKey(
                entity = ReplicaGenerationEntity::class,
                parentColumns = ["partition", "generation"],
                childColumns = ["partition", "generation"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["partition", "generation", "sortKey"], unique = true)],
)
data class ReplicaDocumentEntity(
    val partition: String,
    val generation: String,
    val id: String,
    val version: Long,
    val sortKey: String,
    val payload: String?,
)
