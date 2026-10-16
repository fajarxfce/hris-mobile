package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveAttachmentDto(
    val documentId: String,
    val revisionId: String,
    val fileName: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
    val title: String? = null,
)
