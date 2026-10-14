package dev.fajar.starter.expenses.domain.replica.entities

import dev.fajar.starter.expenses.domain.entities.*

/** Current facts remain available while a separately selected activity page is acquired. */
data class ExpenseReplicaDetails(
    val checkpoint: ExpenseCheckpoint?,
    val snapshot: ExpenseSnapshot<ExpenseView>?,
    val current: ExpenseSnapshot<ExpenseView>? = snapshot,
)
