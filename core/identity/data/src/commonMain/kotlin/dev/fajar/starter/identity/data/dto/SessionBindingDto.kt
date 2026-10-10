package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class SessionBindingDto(
    val serverId: String,
    val audience: String,
    val expiresAtEpochMillis: Long,
)
