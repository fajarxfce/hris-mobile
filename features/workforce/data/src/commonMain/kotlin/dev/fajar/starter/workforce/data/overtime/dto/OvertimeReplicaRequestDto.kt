package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeReplicaRequestDto(
    val snapshot: OvertimeSnapshotDto<OvertimeRequestCacheDto>,
    val acquisitionId: String,
    val sessionId: String,
    val authorizationExpiresAt: String,
    val authorizationExpiryCode: String,
    val pendingHistoryAfter: Long?,
)
