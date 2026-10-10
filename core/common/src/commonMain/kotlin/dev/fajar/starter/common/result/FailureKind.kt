package dev.fajar.starter.common.result

enum class FailureKind {
    /** User dismissed an interaction; coroutine cancellation is still thrown. */
    Cancelled,
    Validation,
    Unauthorized,
    Network,
    Timeout,
    Service,
    Storage,
    Permission,
    AccessDenied,
    Unavailable,
    Unexpected,
}
