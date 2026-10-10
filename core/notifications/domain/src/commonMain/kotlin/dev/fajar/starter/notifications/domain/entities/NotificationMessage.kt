package dev.fajar.starter.notifications.domain.entities

data class NotificationMessage(
    val id: String,
    val title: String,
    val body: String,
    val destination: String,
    val createdAtEpochMillis: Long,
    val read: Boolean = false,
)
