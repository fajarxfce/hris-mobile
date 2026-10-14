package dev.fajar.starter.workforce.domain.replica.entities

import dev.fajar.starter.sync.domain.entities.SyncReference

/** A null record is the versioned deletion supplied by the feed or canonical read. */
data class WorkforceReplicaEntry(val reference: SyncReference, val record: WorkforceRecord?)
