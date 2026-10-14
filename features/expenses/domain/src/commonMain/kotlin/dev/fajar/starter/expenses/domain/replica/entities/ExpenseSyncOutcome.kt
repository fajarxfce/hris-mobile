package dev.fajar.starter.expenses.domain.replica.entities

data class ExpenseSyncOutcome(val caughtUp: Boolean, val pollAfterSeconds: Int)
