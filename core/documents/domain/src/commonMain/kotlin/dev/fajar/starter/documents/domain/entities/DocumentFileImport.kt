package dev.fajar.starter.documents.domain.entities

sealed interface DocumentFileImport {
    data class Stored(val file: LocalDocumentFile) : DocumentFileImport

    data object CapacityExceeded : DocumentFileImport

    data object ScopeChanged : DocumentFileImport

    data object Conflict : DocumentFileImport
}
