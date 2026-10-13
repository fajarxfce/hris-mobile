package dev.fajar.starter.expenses.presentation.navigation

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseDraftRoute(
    val employeeId: String,
    val claimId: String? = null,
    val employmentAfter: String? = null,
)
