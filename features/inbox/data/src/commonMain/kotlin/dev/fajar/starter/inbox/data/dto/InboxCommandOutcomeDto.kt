package dev.fajar.starter.inbox.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class InboxCommandOutcomeDto(
    val phase: String,
    val attempts: Int,
    val receiptVersion: Long? = null,
    val failure: InboxCommandFailureDto? = null,
)

@Serializable
data class InboxCommandFailureDto(
    val kind: String,
    val code: String?,
    val correlationId: String?,
    val retryAfterSeconds: Long?,
)
