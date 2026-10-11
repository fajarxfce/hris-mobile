package dev.fajar.starter.database.entities

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "durable_commands",
    primaryKeys = ["accountKey", "partition", "id"],
    indices =
        [
            Index(value = ["accountKey", "partition", "resourceId"], unique = true),
            Index(value = ["accountKey", "partition", "sortKey"], unique = true),
        ],
)
data class CommandEntity(
    val accountKey: String,
    val partition: String,
    val id: String,
    val resourceId: String,
    val sortKey: String,
    val request: String,
    val state: String,
    val revision: Long,
)
