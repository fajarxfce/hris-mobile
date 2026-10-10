package dev.fajar.starter.files.presentation.queue

sealed interface FilesEffect {
    data object Back : FilesEffect
}
