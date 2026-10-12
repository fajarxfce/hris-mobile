package dev.fajar.starter.documents.domain.entities

sealed interface DocumentFileCompletion {
    data class Ready(val file: LocalDocumentFile) : DocumentFileCompletion

    data object Changed : DocumentFileCompletion

    data object ChecksumMismatch : DocumentFileCompletion
}
