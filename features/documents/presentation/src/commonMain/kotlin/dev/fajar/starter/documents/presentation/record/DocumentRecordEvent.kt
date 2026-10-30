package dev.fajar.starter.documents.presentation.record

sealed interface DocumentRecordEvent {
    data object Opened : DocumentRecordEvent

    data object Closed : DocumentRecordEvent

    data object RefreshRequested : DocumentRecordEvent

    data class HistoryRequested(val after: Long?) : DocumentRecordEvent

    data object VerificationRequested : DocumentRecordEvent

    data object BackRequested : DocumentRecordEvent

    data object EditRequested : DocumentRecordEvent
}
