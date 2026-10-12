package dev.fajar.starter.documents.domain.entities

import kotlin.time.Instant

data class DocumentRevisionProgress(
    val status: DocumentRevisionStatus,
    val uploadedBytes: Long,
    val version: Long,
    val expiresAt: Instant,
    val validationAttempts: Int,
    val failureCode: String?,
)
