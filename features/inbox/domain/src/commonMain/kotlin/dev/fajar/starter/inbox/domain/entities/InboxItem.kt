package dev.fajar.starter.inbox.domain.entities

import kotlin.time.Instant

data class InboxItem(
    val id: String,
    val announcementId: String,
    val publicationId: String,
    val version: Long,
    val title: String,
    val body: String,
    val acknowledgementRequired: Boolean,
    val deliveredAt: Instant,
    val readAt: Instant?,
    val acknowledgedAt: Instant?,
) {
    override fun toString() = "InboxItem([redacted])"
}
