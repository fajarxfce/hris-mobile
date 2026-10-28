package dev.fajar.starter.expenses.data.working.dto

import dev.fajar.starter.expenses.data.dto.ExpenseEmploymentDto
import dev.fajar.starter.expenses.data.dto.ExpenseLineDto
import dev.fajar.starter.expenses.data.dto.ExpenseReferenceValuesDto
import kotlinx.serialization.Serializable

@Serializable
data class ExpenseWorkingCopyDto(
    val claimId: String,
    val employee: ExpenseEmploymentDto,
    val timezone: String,
    val title: String,
    val description: String,
    val lines: List<ExpenseLineDto>,
    val reason: String,
    val expectedVersion: Long?,
    val item: ExpenseWorkingItemDto?,
    val revision: String,
    val savedAt: String,
    val references: ExpenseReferenceValuesDto? = null,
    val schema: Int = 1,
    val hasEdits: Boolean = true,
)
