package dev.fajar.starter.documents.domain.entities

import kotlin.time.Instant

data class DocumentEmploymentSnapshot(
    val access: DocumentAccess,
    val page: DocumentEmploymentPage,
    val after: String?,
    val fetchedAt: Instant,
    val expiresAt: Instant,
)
