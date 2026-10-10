package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class SessionAssuranceDto(
    val required: Boolean,
    val verified: Boolean,
    val setupAvailable: Boolean,
    val validUntil: String?,
    val recentUntil: String?,
)
