package dev.fajar.starter.documents.domain.entities

/** Editable printed facts; preparation validates and freezes their typed value. */
data class DocumentMetadataInput(
    val category: DocumentCategory = DocumentCategory.OTHER,
    val identityNumber: String = "",
    val issuingCountry: String = "",
    val issuedOn: String = "",
    val validUntil: String = "",
) {
    override fun toString() = "DocumentMetadataInput([redacted])"
}
