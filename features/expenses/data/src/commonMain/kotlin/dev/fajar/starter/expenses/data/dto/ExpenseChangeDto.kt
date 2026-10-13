package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseChangeDto(
    val version: Long,
    val draftRevision: Int,
    val status: String,
    val kind: String,
    val actorId: String,
    val reason: String,
    val recordedAt: String,
    val submissionId: String? = null,
)
