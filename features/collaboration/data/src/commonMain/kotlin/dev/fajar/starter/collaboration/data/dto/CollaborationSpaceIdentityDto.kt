package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CollaborationSpaceIdentityResponseDto(val identity: CollaborationSpaceIdentityDto?)

@Serializable
data class CollaborationSpaceIdentityDto(
    val spaceId: String,
    val accountId: String,
    val serverName: String,
    val matrixUserId: String,
    val origin: String,
    val legacyCompanyId: String?,
    val version: Long,
    val createdAt: String,
) {
    override fun toString() = "CollaborationSpaceIdentityDto([redacted])"
}
