package dev.fajar.starter.inbox.domain.entities

import kotlin.time.Instant

/**
 * Immutable user intent. A replacement session may recover it only for the same account/audience.
 */
data class InboxCommand(
    val id: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val itemId: String,
    val expectedVersion: Long,
    val action: InboxAction,
    val createdAt: Instant,
) {
    override fun toString() = "InboxCommand([redacted])"
}
