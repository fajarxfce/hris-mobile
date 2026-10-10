package dev.fajar.starter.common.result

enum class FailureKind {
    Validation,
    Unauthorized,
    Network,
    Timeout,
    Service,
    Storage,
    Unexpected,
}
