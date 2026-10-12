package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentProgressDto(
    val status: String,
    val uploadedBytes: Long,
    val version: Long,
    val expiresAt: String,
    val validationAttempts: Int,
    val failureCode: String?,
)
