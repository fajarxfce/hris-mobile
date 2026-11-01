package dev.fajar.starter.database.entities

import androidx.room.Entity

@Entity(tableName = "command_archives", primaryKeys = ["accountKey", "partition", "id"])
data class CommandArchiveEntity(
    val accountKey: String,
    val partition: String,
    val id: String,
    val resourceId: String,
    val sortKey: String,
    val request: String,
    val state: String,
    val revision: Long,
    val evidence: String,
)
