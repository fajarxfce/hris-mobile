package dev.fajar.starter.common.result

data class Failure(
    val kind: FailureKind,
    val message: String,
    val field: String? = null,
    val violations: Map<String, ValidationIssue> = emptyMap(),
    val code: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val fieldCodes: Map<String, String> = emptyMap(),
    val correlationId: String? = null,
    val retryAfterSeconds: Long? = null,
) {
    override fun toString(): String = "Failure(kind=$kind, code=$code)"
}
