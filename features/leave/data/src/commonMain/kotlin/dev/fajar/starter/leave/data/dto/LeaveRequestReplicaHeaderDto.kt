package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

/** The acquisition stamp is independent of later sync and historical-page progress. */
@Serializable
data class LeaveRequestReplicaHeaderDto(
    val snapshot: LeaveRequestSnapshotDto,
    val acquisitionId: String,
    val sessionId: String,
    val authorizationExpiresAt: String,
    val authorizationExpiryCode: String,
    val dayBatches: Int,
    val pendingHistoryAfter: Long?,
)
