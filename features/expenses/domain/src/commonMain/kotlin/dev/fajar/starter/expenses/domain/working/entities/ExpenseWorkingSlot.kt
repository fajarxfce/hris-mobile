package dev.fajar.starter.expenses.domain.working.entities

/** One working copy for each existing claim and one new-claim editor per owned employment. */
sealed interface ExpenseWorkingSlot {
    data class Creation(val employeeId: String) : ExpenseWorkingSlot

    data class Claim(val claimId: String) : ExpenseWorkingSlot
}
