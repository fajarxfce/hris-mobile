package dev.fajar.starter.documents.domain.entities

data class DocumentFileDeclaration(val name: String, val mediaType: String, val size: Long) {
    override fun toString() = "DocumentFileDeclaration([redacted])"
}
