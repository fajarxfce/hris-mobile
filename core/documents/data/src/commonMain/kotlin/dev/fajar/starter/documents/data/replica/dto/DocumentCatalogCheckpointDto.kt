package dev.fajar.starter.documents.data.replica.dto

import dev.fajar.starter.documents.data.dto.DocumentEmploymentDto
import kotlinx.serialization.Serializable

@Serializable
data class DocumentCatalogCheckpointDto(
    val permissions: Set<String>,
    val syncSessionId: String,
    val sessionExpiresAt: String,
    val authorizationExpiresAt: String,
    val authorizationExpiryCode: String,
    val phase: String,
    val cursor: String?,
    val refreshedAt: String?,
    val refreshAfter: String,
    val pollAt: String?,
    val employments: List<DocumentEmploymentDto>,
    val schema: Int = 1,
)
