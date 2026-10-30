package dev.fajar.starter.documents.domain.entities

import kotlinx.datetime.LocalDate

/** Printed business facts are independent of upload deadlines and tax residence. */
data class DocumentBusinessMetadata(
    val category: DocumentCategory,
    val identityNumber: String?,
    val issuingCountry: String?,
    val issuedOn: LocalDate?,
    val validUntil: LocalDate?,
) {
    override fun toString() = "DocumentBusinessMetadata([redacted])"
}
