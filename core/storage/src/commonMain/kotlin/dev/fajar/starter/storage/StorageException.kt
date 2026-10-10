package dev.fajar.starter.storage

/** Recognizes platform SDK failures while preserving fatal Kotlin errors. */
internal expect fun storageExceptionOrNull(cause: Throwable): Exception?
