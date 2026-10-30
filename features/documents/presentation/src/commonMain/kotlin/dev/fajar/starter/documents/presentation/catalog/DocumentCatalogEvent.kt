package dev.fajar.starter.documents.presentation.catalog

import dev.fajar.starter.documents.domain.entities.DocumentClassification

sealed interface DocumentCatalogEvent {
    data object Opened : DocumentCatalogEvent

    data object Closed : DocumentCatalogEvent

    data object RefreshRequested : DocumentCatalogEvent

    data object FilesRequested : DocumentCatalogEvent

    data object EmploymentRequested : DocumentCatalogEvent

    data object EmploymentDismissed : DocumentCatalogEvent

    data class EmploymentSelected(val id: String) : DocumentCatalogEvent

    data class ClassificationChanged(val value: DocumentClassification) : DocumentCatalogEvent

    data class PageRequested(val after: String?) : DocumentCatalogEvent

    data class DownloadRequested(val revisionId: String) : DocumentCatalogEvent

    data class DetailsRequested(val documentId: String) : DocumentCatalogEvent

    data object VerificationRequested : DocumentCatalogEvent

    data object BackRequested : DocumentCatalogEvent
}
