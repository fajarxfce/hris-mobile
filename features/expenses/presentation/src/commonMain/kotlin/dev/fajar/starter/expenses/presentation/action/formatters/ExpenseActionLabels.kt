package dev.fajar.starter.expenses.presentation.action.formatters

import dev.fajar.starter.expenses.presentation.action.ExpenseActionMode
import dev.fajar.starter.localization.AppString

fun ExpenseActionMode.title(): AppString =
    when (this) {
        ExpenseActionMode.SUBMIT -> AppString.ExpenseSubmitClaim
        ExpenseActionMode.CANCEL -> AppString.ExpenseCancelClaim
        ExpenseActionMode.WITHDRAW -> AppString.ExpenseWithdrawSubmission
        ExpenseActionMode.RECOVER -> AppString.ExpenseRecoverRequest
    }

fun ExpenseActionMode.hint(): AppString =
    when (this) {
        ExpenseActionMode.SUBMIT -> AppString.ExpenseSubmitHint
        ExpenseActionMode.CANCEL -> AppString.ExpenseCancelHint
        ExpenseActionMode.WITHDRAW -> AppString.ExpenseWithdrawHint
        ExpenseActionMode.RECOVER -> AppString.ExpenseNoPendingRequest
    }
