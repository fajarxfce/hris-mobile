package dev.fajar.starter.people.data.replica.dto

import kotlinx.serialization.Serializable

@Serializable
data class DirectoryCheckpointDto(
    val permissions: Set<String>,
    val syncSessionId: String,
    val phase: String,
    val cursor: String?,
    val refreshedAt: String?,
    val refreshAfter: String,
    val pollAt: String?,
    val timezone: String,
    val schema: Int = 1,
)
