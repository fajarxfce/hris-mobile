package dev.fajar.starter.documents.presentation.changes

sealed interface DocumentMetadataChangesEvent {
    data object Opened : DocumentMetadataChangesEvent

    data object Closed : DocumentMetadataChangesEvent

    data object RefreshRequested : DocumentMetadataChangesEvent

    data class EditorRequested(val documentId: String) : DocumentMetadataChangesEvent

    data class RecoveryRequested(val documentId: String) : DocumentMetadataChangesEvent

    data class PageRequested(val after: String?) : DocumentMetadataChangesEvent

    data object VerificationRequested : DocumentMetadataChangesEvent

    data object BackRequested : DocumentMetadataChangesEvent
}

sealed interface DocumentMetadataChangesEffect {
    data class Editor(val documentId: String) : DocumentMetadataChangesEffect

    data class Recovery(val documentId: String) : DocumentMetadataChangesEffect

    data object VerifyAccount : DocumentMetadataChangesEffect

    data object ScrollToStart : DocumentMetadataChangesEffect

    data object Back : DocumentMetadataChangesEffect
}
