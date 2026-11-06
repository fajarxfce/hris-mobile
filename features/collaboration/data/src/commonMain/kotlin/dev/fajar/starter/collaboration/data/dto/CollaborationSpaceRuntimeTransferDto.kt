package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CollaborationLegacyRuntimeRequestDto(
    val deviceId: String,
    val expectedDeviceVersion: Long,
    val fingerprint: String,
)

@Serializable
data class CollaborationLegacyRuntimeTargetDto(val spaceId: String, val deviceId: String)

@Serializable
data class CollaborationLegacyRuntimeDrainDto(
    val target: CollaborationLegacyRuntimeTargetDto,
    val runtimeVersion: Long,
    val activeRequests: Int,
    val latestRequestExpiresAt: String?,
    val observedAt: String,
)
