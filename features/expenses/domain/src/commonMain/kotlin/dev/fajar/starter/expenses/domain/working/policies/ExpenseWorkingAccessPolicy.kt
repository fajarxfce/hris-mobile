package dev.fajar.starter.expenses.domain.working.policies

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.expenses.domain.policies.expenseFailureInvalidatesCache

/** Expired action review requires another server review; it never expires owned editor input. */
fun expenseFailureRestrictsWorkingCopy(failure: Failure): Boolean =
    failure.code != "client.expense_preparation_expired" && expenseFailureInvalidatesCache(failure)
