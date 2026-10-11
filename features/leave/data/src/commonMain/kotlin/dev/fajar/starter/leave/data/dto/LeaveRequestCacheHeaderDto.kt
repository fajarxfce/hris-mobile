package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

/** Counts bind the complete atomic snapshot; records never expose request IDs as storage keys. */
@Serializable
data class LeaveRequestCacheHeaderDto(
    val snapshot: LeaveRequestSnapshotDto,
    val dayBatches: Int,
    val historyCount: Int,
)
