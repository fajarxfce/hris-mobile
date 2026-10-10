package dev.fajar.starter.network

import dev.fajar.starter.observability.*
import io.ktor.client.plugins.ResponseException

fun reportNetworkException(exception: Exception) {
    if (exception is ResponseException || exception is RequestFailureException) {
        Diagnostics.record(
            Diagnostic(
                DiagnosticArea.Network,
                DiagnosticKind.OperationFailed,
                exception::class.simpleName,
                (exception as? ResponseException)?.response?.status?.value,
            )
        )
    } else Diagnostics.failure(DiagnosticArea.Network, exception)
}
