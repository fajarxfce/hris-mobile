package dev.fajar.starter.expenses.domain.entities

/** New claims share their employment's slot; existing-claim actions share the claim's slot. */
sealed interface ExpenseCommandSlot {
    data class Creation(val employeeId: String) : ExpenseCommandSlot

    data class Claim(val claimId: String) : ExpenseCommandSlot
}
