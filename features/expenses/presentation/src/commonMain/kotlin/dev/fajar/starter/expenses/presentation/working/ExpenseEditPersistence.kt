@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.presentation.working

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.expenses.domain.entities.ExpenseDraftPreparation
import dev.fajar.starter.expenses.domain.working.entities.ExpenseWorkingCopy
import kotlin.uuid.Uuid

/** Bounded editor state: one acknowledged copy, the latest input, and one pending mutation. */
data class ExpenseEditPersistence(
    val stored: ExpenseWorkingCopy,
    val edited: ExpenseWorkingCopy = stored,
    val pending: ExpenseWorkingMutation? = null,
    val writing: Boolean = false,
    val failure: Failure? = null,
) {
    val saved: Boolean
        get() = pending == null && edited.revision == stored.revision && failure == null

    fun edit(value: ExpenseWorkingCopy): ExpenseEditPersistence =
        if (value == edited) this
        else copy(edited = value.copy(revision = Uuid.random().toString()))

    fun acknowledge(value: ExpenseWorkingCopy): ExpenseEditPersistence =
        copy(
            stored = value,
            edited = if (edited.revision == value.revision) value else edited,
            pending = null,
            writing = false,
            failure = failure?.takeIf { it.code == "client.expense_working_copy_changed" },
        )
}

data class ExpenseWorkingMutation(
    val previous: ExpenseWorkingCopy,
    val updated: ExpenseWorkingCopy,
    val reload: ExpenseDraftPreparation? = null,
)
