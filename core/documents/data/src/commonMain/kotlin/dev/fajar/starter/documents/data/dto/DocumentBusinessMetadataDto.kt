package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentBusinessMetadataDto(
    val category: String,
    val identityNumber: String?,
    val issuingCountry: String?,
    val issuedOn: String?,
    val validUntil: String?,
) {
    override fun toString() = "DocumentBusinessMetadataDto([redacted])"
}
