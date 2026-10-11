package dev.fajar.starter.leave.domain.entities

/** Metadata for the exact accepted document revision; never a local file path or URL. */
data class LeaveAttachment(
    val documentId: String,
    val revisionId: String,
    val fileName: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
)
