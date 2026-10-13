package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable data class ExpenseCommandReceiptDto(val id: String, val version: Long)
