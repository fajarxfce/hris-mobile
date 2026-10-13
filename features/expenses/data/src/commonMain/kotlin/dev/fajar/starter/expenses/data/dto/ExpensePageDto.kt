package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable data class ExpensePageDto<T>(val items: List<T>, val nextCursor: String? = null)
