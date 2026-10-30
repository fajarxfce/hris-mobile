package dev.fajar.starter.documents.presentation.catalog

sealed interface DocumentCatalogEffect {
    data object OpenFiles : DocumentCatalogEffect

    data object OpenMetadataChanges : DocumentCatalogEffect

    data class OpenDownload(val fileId: String) : DocumentCatalogEffect

    data class OpenDetails(val documentId: String) : DocumentCatalogEffect

    data object ScrollToStart : DocumentCatalogEffect

    data object VerifyAccount : DocumentCatalogEffect

    data object Back : DocumentCatalogEffect
}
