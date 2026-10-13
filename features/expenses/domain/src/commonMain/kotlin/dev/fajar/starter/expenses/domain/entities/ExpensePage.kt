package dev.fajar.starter.expenses.domain.entities

data class ExpensePage<T>(val items: List<T>, val nextCursor: String? = null)
