package dev.fajar.starter.expenses.data.replica.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseReadAdmissionDto(val revision: String, val kind: String, val code: String?)
