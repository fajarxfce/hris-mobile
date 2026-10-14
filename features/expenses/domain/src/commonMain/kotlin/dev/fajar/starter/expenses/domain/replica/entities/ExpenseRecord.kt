package dev.fajar.starter.expenses.domain.replica.entities

import dev.fajar.starter.expenses.domain.entities.*

sealed interface ExpenseRecord {
    data class Claim(val snapshot: ExpenseSnapshot<ExpenseClaimRecord>) : ExpenseRecord

    data class Payments(val progress: ExpenseClaimPayments) : ExpenseRecord
}
