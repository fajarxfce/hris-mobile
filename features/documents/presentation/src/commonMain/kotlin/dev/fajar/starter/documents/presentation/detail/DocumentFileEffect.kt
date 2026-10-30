package dev.fajar.starter.documents.presentation.detail

sealed interface DocumentFileEffect {
    data object Back : DocumentFileEffect

    data object VerifyAccount : DocumentFileEffect

    data object ScrollToStart : DocumentFileEffect

    data object ScrollEmploymentsToStart : DocumentFileEffect
}
