package dev.fajar.starter.common.result

data class Failure(val kind: FailureKind, val message: String, val field: String? = null)
