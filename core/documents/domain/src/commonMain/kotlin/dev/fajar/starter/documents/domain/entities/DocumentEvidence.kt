package dev.fajar.starter.documents.domain.entities

import kotlin.time.Instant

/** Accepted metadata for one immutable revision; it is not a content access grant. */
data class DocumentEvidence(
    val documentId: String,
    val revisionId: String,
    val revisionNumber: Int,
    val title: String,
    val fileName: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
    val validatedAt: Instant,
)
