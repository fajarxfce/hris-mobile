package dev.fajar.starter.documents.domain.entities

sealed interface DocumentUploadAction {
    data object CheckStatus : DocumentUploadAction

    data object RetryValidation : DocumentUploadAction

    data class Cancel(val reason: String) : DocumentUploadAction
}
