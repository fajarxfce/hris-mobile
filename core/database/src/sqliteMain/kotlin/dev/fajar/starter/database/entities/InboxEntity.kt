package dev.fajar.starter.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inbox")
data class InboxEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val destination: String,
    val createdAtEpochMillis: Long,
    val read: Boolean,
)
