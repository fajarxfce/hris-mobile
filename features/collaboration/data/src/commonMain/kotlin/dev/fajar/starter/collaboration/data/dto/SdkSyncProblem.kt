package dev.fajar.starter.collaboration.data.dto

/** Sanitized protocol evidence, never provider descriptions or credentials. */
internal data class SdkSyncProblem(
    val matrixCode: String?,
    val retryAfterMillis: Long? = null,
    val terminal: Boolean = false,
)
