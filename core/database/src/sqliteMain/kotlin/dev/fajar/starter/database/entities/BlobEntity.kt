package dev.fajar.starter.database.entities

import androidx.room.Entity

@Entity(tableName = "durable_blobs", primaryKeys = ["accountKey", "partition", "id"])
data class BlobEntity(
    val accountKey: String,
    val partition: String,
    val id: String,
    val size: Long,
    val metadata: String,
    val storedBytes: Long,
    val completion: String?,
    val revision: Long,
    val state: String?,
)
