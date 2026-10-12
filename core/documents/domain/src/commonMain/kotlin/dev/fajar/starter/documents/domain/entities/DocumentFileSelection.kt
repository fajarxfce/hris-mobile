package dev.fajar.starter.documents.domain.entities

/** Untrusted facts from a user-selected input. The request ID remains owned by its use case. */
data class DocumentFileSelection(val name: String?, val mediaType: String?, val size: Long?) {
    override fun toString() = "DocumentFileSelection([redacted])"
}
