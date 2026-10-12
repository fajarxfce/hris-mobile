package dev.fajar.starter.documents.domain.entities

enum class DocumentRevisionStatus {
    UPLOADING,
    VALIDATING,
    VALIDATION_FAILED,
    READY,
    REJECTED,
    CANCELLED,
    EXPIRED,
    RETIRED,
}
