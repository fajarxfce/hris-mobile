package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
sealed interface ExpenseCommandPayloadDto {
    @Serializable
    data class SaveDraft(
        val expectedVersion: Long?,
        val title: String,
        val description: String,
        val lines: List<ExpenseLineDto>,
    ) : ExpenseCommandPayloadDto

    @Serializable
    data class Submit(val expectedVersion: Long, val submissionId: String) :
        ExpenseCommandPayloadDto

    @Serializable data class Cancel(val expectedVersion: Long) : ExpenseCommandPayloadDto

    @Serializable
    data class Withdraw(val expectedVersion: Long, val submissionId: String) :
        ExpenseCommandPayloadDto
}
