package dev.fajar.starter.expenses.data.working.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseWorkingCopyHeaderDto(val claimId: String, val size: Int, val schema: Int = 1)
