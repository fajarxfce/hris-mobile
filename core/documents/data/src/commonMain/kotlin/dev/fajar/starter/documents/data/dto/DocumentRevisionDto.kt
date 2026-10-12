package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentRevisionDto(
    val id: String,
    val documentId: String,
    val number: Int,
    val fileName: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
    val status: String,
    val uploadedBytes: Long,
    val chunkSize: Int,
    val version: Long,
    val createdBy: String,
    val createdAt: String,
    val expiresAt: String,
    val reason: String,
    val validationJobId: String? = null,
    val validationAttempts: Int,
    val failureCode: String? = null,
    val validatedAt: String? = null,
)
