package dev.fajar.starter.documents.domain.entities

import kotlin.time.Instant

data class DocumentEvidenceSnapshot(
    val access: DocumentAccess,
    val query: DocumentEvidenceQuery,
    val page: DocumentEvidencePage,
    val fetchedAt: Instant,
    val expiresAt: Instant,
)
