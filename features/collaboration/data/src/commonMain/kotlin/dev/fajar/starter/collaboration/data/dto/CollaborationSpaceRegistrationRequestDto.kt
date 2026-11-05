package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CollaborationSpaceRegistrationRequestDto(
    val platform: String,
    val label: String,
    val origin: String,
    val legacy: CollaborationSpaceLegacyPositionDto? = null,
    val legacyKeyFingerprint: String? = null,
) {
    override fun toString() = "CollaborationSpaceRegistrationRequestDto([redacted])"
}

@Serializable
data class CollaborationSpaceLegacyPositionDto(val companyId: String, val deviceId: String)
