package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentDownloadSourceDto(
    val employmentId: String,
    val classification: String,
    val evidence: DocumentEvidenceDto,
) {
    override fun toString() = "DocumentDownloadSourceDto([redacted])"
}
