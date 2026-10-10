package dev.fajar.starter.storage

import dev.fajar.starter.observability.DiagnosticArea
import dev.fajar.starter.observability.Diagnostics

fun reportStorageException(exception: Exception) {
    Diagnostics.failure(DiagnosticArea.Storage, exception)
}
