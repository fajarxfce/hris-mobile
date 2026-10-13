package dev.fajar.starter.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "replica_tags",
    primaryKeys = ["ownerKey", "partition", "generation", "id", "tag"],
    foreignKeys =
        [
            ForeignKey(
                entity = ReplicaDocumentEntity::class,
                parentColumns = ["ownerKey", "partition", "generation", "id"],
                childColumns = ["ownerKey", "partition", "generation", "id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices =
        [Index(value = ["ownerKey", "partition", "generation", "tag", "sortKey"], unique = true)],
)
data class ReplicaTagEntity(
    val ownerKey: String,
    val partition: String,
    val generation: String,
    val id: String,
    val tag: String,
    val sortKey: String,
)
