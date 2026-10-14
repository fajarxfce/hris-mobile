package dev.fajar.starter.expenses.domain.replica.entities

import dev.fajar.starter.sync.domain.entities.SyncReference

/** A null record is a versioned deletion supplied by the feed. */
data class ExpenseReplicaEntry(val reference: SyncReference, val record: ExpenseRecord?)
