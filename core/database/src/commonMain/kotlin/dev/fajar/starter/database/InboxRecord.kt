package dev.fajar.starter.database

import kotlinx.serialization.Serializable

@Serializable
data class InboxRecord(
    val id: String,
    val title: String,
    val body: String,
    val destination: String,
    val createdAtEpochMillis: Long,
    val read: Boolean = false,
)
