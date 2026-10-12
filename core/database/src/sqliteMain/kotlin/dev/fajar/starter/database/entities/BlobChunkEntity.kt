package dev.fajar.starter.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "durable_blob_chunks",
    primaryKeys = ["accountKey", "partition", "id", "offset"],
    foreignKeys =
        [
            ForeignKey(
                entity = BlobEntity::class,
                parentColumns = ["accountKey", "partition", "id"],
                childColumns = ["accountKey", "partition", "id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class BlobChunkEntity(
    val accountKey: String,
    val partition: String,
    val id: String,
    val offset: Long,
    val length: Int,
    val payload: String,
)
