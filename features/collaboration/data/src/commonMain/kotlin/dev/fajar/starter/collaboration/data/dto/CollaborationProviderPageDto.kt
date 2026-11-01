package dev.fajar.starter.collaboration.data.dto

import dev.fajar.starter.collaboration.domain.entities.CollaborationProviderHistoryEvent

internal data class CollaborationProviderPageDto(
    val start: String,
    val next: String?,
    val events: List<CollaborationProviderHistoryEvent>,
) {
    override fun toString() = "CollaborationProviderPageDto([redacted])"
}
