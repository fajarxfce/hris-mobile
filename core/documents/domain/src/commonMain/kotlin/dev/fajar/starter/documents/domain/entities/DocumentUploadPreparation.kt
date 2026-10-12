package dev.fajar.starter.documents.domain.entities

import kotlin.time.Instant

data class DocumentUploadPreparation(
    val access: DocumentAccess,
    val file: LocalDocumentFile,
    val employments: DocumentEmploymentPage,
    val after: String?,
    val fetchedAt: Instant,
    val expiresAt: Instant,
)
