package dev.fajar.starter.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "replica_documents",
    primaryKeys = ["ownerKey", "partition", "generation", "id"],
    foreignKeys =
        [
            ForeignKey(
                entity = ReplicaGenerationEntity::class,
                parentColumns = ["ownerKey", "partition", "generation"],
                childColumns = ["ownerKey", "partition", "generation"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["ownerKey", "partition", "generation", "sortKey"], unique = true)],
)
data class ReplicaDocumentEntity(
    val ownerKey: String,
    val partition: String,
    val generation: String,
    val id: String,
    val version: Long,
    val sortKey: String,
    val payload: String?,
)
