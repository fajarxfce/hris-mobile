package dev.fajar.starter.expenses.domain.replica.entities

import dev.fajar.starter.expenses.domain.entities.ExpenseDirectory

data class ExpenseReplicaDirectory(
    val checkpoint: ExpenseCheckpoint?,
    val directory: ExpenseDirectory?,
)
