package dev.fajar.starter.expenses.domain.entities

sealed interface ExpenseCommandPayload {
    data class SaveDraft(
        val expectedVersion: Long?,
        val title: String,
        val description: String,
        val lines: List<ExpenseLine>,
    ) : ExpenseCommandPayload {
        override fun toString() = "SaveDraft([redacted])"
    }

    data class Submit(val expectedVersion: Long, val submissionId: String) : ExpenseCommandPayload

    data class Cancel(val expectedVersion: Long) : ExpenseCommandPayload

    data class Withdraw(val expectedVersion: Long, val submissionId: String) : ExpenseCommandPayload
}
