package dev.fajar.starter.documents.presentation.catalog

sealed interface DocumentCatalogEffect {
    data object OpenFiles : DocumentCatalogEffect

    data class OpenDownload(val fileId: String) : DocumentCatalogEffect

    data object ScrollToStart : DocumentCatalogEffect

    data object ScrollEmploymentsToStart : DocumentCatalogEffect

    data object VerifyAccount : DocumentCatalogEffect

    data object Back : DocumentCatalogEffect
}
