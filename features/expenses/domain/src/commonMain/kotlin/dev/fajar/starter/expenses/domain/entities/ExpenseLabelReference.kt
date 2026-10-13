package dev.fajar.starter.expenses.domain.entities

/** Null display values represent an unavailable reference, without revealing its owner. */
data class ExpenseLabelReference(
    val id: String,
    val code: String? = null,
    val name: String? = null,
    val active: Boolean? = null,
)
