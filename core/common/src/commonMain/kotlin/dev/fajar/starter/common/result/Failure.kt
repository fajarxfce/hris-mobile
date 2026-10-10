package dev.fajar.starter.common.result

data class Failure(
    val kind: FailureKind,
    val message: String,
    val field: String? = null,
    val violations: Map<String, ValidationIssue> = emptyMap(),
)
