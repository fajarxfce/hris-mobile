package dev.fajar.starter.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val id: String,
    val direction: String,
    val name: String,
    val mediaType: String,
    val size: Long,
    val createdAtEpochMillis: Long,
    val resourceId: String? = null,
    val resourceVersion: String? = null,
    val offset: Long = 0,
    val storedBytes: Long = 0,
    val status: String = "Staging",
    val version: Long = 0,
    val failureKind: String? = null,
)
