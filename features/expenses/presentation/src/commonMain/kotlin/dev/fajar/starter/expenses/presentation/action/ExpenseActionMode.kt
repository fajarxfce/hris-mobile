package dev.fajar.starter.expenses.presentation.action

import dev.fajar.starter.expenses.domain.entities.ExpenseCommandPayload
import dev.fajar.starter.expenses.domain.entities.ExpenseRequestedAction

enum class ExpenseActionMode(val action: ExpenseRequestedAction?) {
    SUBMIT(ExpenseRequestedAction.SUBMIT),
    CANCEL(ExpenseRequestedAction.CANCEL),
    WITHDRAW(ExpenseRequestedAction.WITHDRAW),
    RECOVER(null),
}

internal fun ExpenseCommandPayload.toActionMode(): ExpenseActionMode =
    when (this) {
        is ExpenseCommandPayload.Submit -> ExpenseActionMode.SUBMIT
        is ExpenseCommandPayload.Cancel -> ExpenseActionMode.CANCEL
        is ExpenseCommandPayload.Withdraw -> ExpenseActionMode.WITHDRAW
        is ExpenseCommandPayload.SaveDraft -> ExpenseActionMode.RECOVER
    }
