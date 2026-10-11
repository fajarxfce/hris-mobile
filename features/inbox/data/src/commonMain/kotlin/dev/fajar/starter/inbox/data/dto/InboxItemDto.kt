package dev.fajar.starter.inbox.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class InboxItemDto(
    val id: String,
    val announcementId: String,
    val publicationId: String,
    val version: Long,
    val title: String,
    val body: String,
    val acknowledgementRequired: Boolean,
    val deliveredAt: String,
    val readAt: String? = null,
    val acknowledgedAt: String? = null,
) {
    override fun toString() = "InboxItemDto([redacted])"
}
