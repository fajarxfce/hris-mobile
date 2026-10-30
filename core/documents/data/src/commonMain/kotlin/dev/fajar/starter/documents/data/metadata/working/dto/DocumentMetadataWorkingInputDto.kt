package dev.fajar.starter.documents.data.metadata.working.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentMetadataWorkingInputDto(
    val category: String,
    val identityNumber: String,
    val issuingCountry: String,
    val issuedOn: String,
    val validUntil: String,
) {
    override fun toString() = "DocumentMetadataWorkingInputDto([redacted])"
}
