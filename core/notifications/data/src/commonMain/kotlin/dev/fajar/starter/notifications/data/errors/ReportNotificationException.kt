package dev.fajar.starter.notifications.data.errors

import dev.fajar.starter.observability.DiagnosticArea
import dev.fajar.starter.observability.Diagnostics

/** Reports an SDK failure without notification payloads, tokens or raw error messages. */
fun reportNotificationException(error: Exception) {
    Diagnostics.failure(DiagnosticArea.Notification, error)
}
