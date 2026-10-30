package dev.fajar.starter.documents.data.metadata.commands.dto

import dev.fajar.starter.documents.data.dto.DocumentBusinessMetadataDto
import kotlinx.serialization.Serializable

@Serializable
data class DocumentMetadataCommandDto(
    val accountId: String,
    val audience: String,
    val companyId: String,
    val employmentId: String,
    val documentTitle: String,
    val operationId: String,
    val documentId: String,
    val expectedVersion: Long,
    val metadata: DocumentBusinessMetadataDto,
    val reason: String,
    val createdAt: String,
) {
    override fun toString() = "DocumentMetadataCommandDto([redacted])"
}
