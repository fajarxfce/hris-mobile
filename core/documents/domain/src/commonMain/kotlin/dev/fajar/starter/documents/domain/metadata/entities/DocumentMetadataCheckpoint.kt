package dev.fajar.starter.documents.domain.metadata.entities

import kotlin.time.Instant

/** History is acquired contiguously from revision zero; current facts have no local expiry. */
data class DocumentMetadataCheckpoint(
    val generation: String,
    val revision: Long,
    val head: DocumentMetadataHead,
    val refreshedAt: Instant,
    val historyThrough: Long?,
)
