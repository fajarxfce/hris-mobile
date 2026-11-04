package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CollaborationSpaceObservationDto(
    val spaceId: String,
    val accountId: String,
    val nativeSessionId: String,
    val nativeSessionVersion: Long,
    val credentialVersion: Long,
    val admittedAt: String,
    val expiresAt: String,
)

@Serializable data class CollaborationSpaceContactCompanyDto(val id: String, val name: String)

@Serializable
data class CollaborationSpaceContactDto(
    val accountId: String,
    val displayName: String,
    val companies: List<CollaborationSpaceContactCompanyDto>,
    val hasMoreCompanies: Boolean,
)

@Serializable
data class CollaborationSpaceContactPageDto(
    val items: List<CollaborationSpaceContactDto>,
    val nextCursor: String?,
)
