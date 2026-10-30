package dev.fajar.starter.documents.presentation.editor

import dev.fajar.starter.documents.domain.entities.DocumentCategory

sealed interface DocumentMetadataEditorEvent {
    data object Opened : DocumentMetadataEditorEvent

    data object Stopped : DocumentMetadataEditorEvent

    data object RefreshRequested : DocumentMetadataEditorEvent

    data object CategoryRequested : DocumentMetadataEditorEvent

    data object CategoryDismissed : DocumentMetadataEditorEvent

    data class CategorySelected(val value: DocumentCategory) : DocumentMetadataEditorEvent

    data class NumberChanged(val value: String) : DocumentMetadataEditorEvent

    data class CountryChanged(val value: String) : DocumentMetadataEditorEvent

    data class IssuedOnChanged(val value: String) : DocumentMetadataEditorEvent

    data class ValidUntilChanged(val value: String) : DocumentMetadataEditorEvent

    data class ReasonChanged(val value: String) : DocumentMetadataEditorEvent

    data object ConfirmRequested : DocumentMetadataEditorEvent

    data object RetryDelivery : DocumentMetadataEditorEvent

    data object ReviewRequested : DocumentMetadataEditorEvent

    data object DiscardRequested : DocumentMetadataEditorEvent

    data object RestartRequested : DocumentMetadataEditorEvent

    data object ReloadRequested : DocumentMetadataEditorEvent

    data object RetryLocalSave : DocumentMetadataEditorEvent

    data object DialogConfirmed : DocumentMetadataEditorEvent

    data object DialogDismissed : DocumentMetadataEditorEvent

    data object VerificationRequested : DocumentMetadataEditorEvent

    data object BackRequested : DocumentMetadataEditorEvent
}

sealed interface DocumentMetadataEditorEffect {
    data object Back : DocumentMetadataEditorEffect

    data object ScrollToStart : DocumentMetadataEditorEffect

    data object VerifyAccount : DocumentMetadataEditorEffect
}
