package dev.fajar.starter.documents.domain.entities

sealed interface DocumentFileReservation {
    data class Stored(val file: LocalDocumentFile) : DocumentFileReservation

    data object CapacityExceeded : DocumentFileReservation

    data object ScopeChanged : DocumentFileReservation

    data object Conflict : DocumentFileReservation
}
