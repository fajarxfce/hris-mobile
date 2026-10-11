package dev.fajar.starter.location.data.errors

import dev.fajar.starter.observability.*

fun reportLocationException(error: Exception) {
    Diagnostics.failure(DiagnosticArea.Application, error)
}
