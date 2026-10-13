package dev.fajar.starter.expenses.presentation.commands

import dev.fajar.starter.expenses.domain.entities.*

fun ExpenseCommandRecord.canRetry() =
    outcome is ExpenseCommandOutcome.Pending || outcome is ExpenseCommandOutcome.Unconfirmed

fun ExpenseCommandRecord.canDiscard() =
    outcome is ExpenseCommandOutcome.Pending || outcome is ExpenseCommandOutcome.Rejected
