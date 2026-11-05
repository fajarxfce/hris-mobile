package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CollaborationSummaryFactDto(
    val conversationId: String,
    val revision: Long,
    val summary: CollaborationDirectSummaryDto?,
)

@Serializable
data class CollaborationSummaryChangeDto(val position: Long, val fact: CollaborationSummaryFactDto)

@Serializable
data class CollaborationSummaryBootstrapDto(
    val items: List<CollaborationSummaryFactDto>,
    val nextPage: String?,
    val changesCursor: String?,
    val pending: Boolean,
    val serverTime: String,
)

@Serializable
data class CollaborationSummaryChangesDto(
    val items: List<CollaborationSummaryChangeDto>,
    val nextCursor: String,
    val hasMore: Boolean,
    val pending: Boolean,
    val serverTime: String,
)
