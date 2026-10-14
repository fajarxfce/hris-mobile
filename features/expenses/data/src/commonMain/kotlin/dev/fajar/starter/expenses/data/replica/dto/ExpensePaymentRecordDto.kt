package dev.fajar.starter.expenses.data.replica.dto

import dev.fajar.starter.expenses.data.dto.ExpenseClaimPaymentsDto
import kotlinx.serialization.Serializable

/** Null retains a versioned feed deletion without retaining any payment facts. */
@Serializable data class ExpensePaymentRecordDto(val progress: ExpenseClaimPaymentsDto?)
