package dev.fajar.starter.documents.presentation.record

sealed interface DocumentRecordEffect {
    data object Back : DocumentRecordEffect

    data class Edit(val documentId: String) : DocumentRecordEffect

    data object VerifyAccount : DocumentRecordEffect

    data object ScrollToStart : DocumentRecordEffect
}
