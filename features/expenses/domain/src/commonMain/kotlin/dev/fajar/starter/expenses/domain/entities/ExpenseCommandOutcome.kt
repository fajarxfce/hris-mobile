package dev.fajar.starter.expenses.domain.entities

import dev.fajar.starter.common.result.Failure

sealed interface ExpenseCommandOutcome {
    data object Pending : ExpenseCommandOutcome

    /**
     * Written before HTTP delivery; a missing response cannot establish whether a commit occurred.
     */
    data class Unconfirmed(val failure: Failure? = null) : ExpenseCommandOutcome

    data class Rejected(val failure: Failure) : ExpenseCommandOutcome

    data class Confirmed(val version: Long) : ExpenseCommandOutcome
}
