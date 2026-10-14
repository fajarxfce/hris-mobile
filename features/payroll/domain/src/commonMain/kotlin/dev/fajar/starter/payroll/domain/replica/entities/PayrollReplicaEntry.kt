package dev.fajar.starter.payroll.domain.replica.entities

import dev.fajar.starter.sync.domain.entities.SyncReference

/** A null record is a versioned deletion supplied by the feed. */
data class PayrollReplicaEntry(val reference: SyncReference, val record: PayrollRecord?)
