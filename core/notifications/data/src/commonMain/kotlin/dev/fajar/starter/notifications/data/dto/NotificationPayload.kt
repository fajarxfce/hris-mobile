package dev.fajar.starter.notifications.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class NotificationPayload(
    val id: String,
    val title: String,
    val body: String,
    val destination: String,
)
