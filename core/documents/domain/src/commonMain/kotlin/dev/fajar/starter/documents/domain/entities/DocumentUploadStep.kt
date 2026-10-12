package dev.fajar.starter.documents.domain.entities

/** Each mutation retains its original operation ID, offset and version until confirmed. */
sealed interface DocumentUploadStep {
    data class Start(val operationId: String) : DocumentUploadStep

    data class Chunk(val operationId: String, val offset: Long, val version: Long) :
        DocumentUploadStep

    data class Validate(val operationId: String, val version: Long) : DocumentUploadStep

    data class Processing(val version: Long) : DocumentUploadStep

    data class Finished(val revision: DocumentRevisionProgress) : DocumentUploadStep

    data class Cancel(val operationId: String, val version: Long, val reason: String) :
        DocumentUploadStep
}
