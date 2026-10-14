package dev.fajar.starter.workforce.domain.replica.entities

import dev.fajar.starter.workforce.domain.entities.WorkforceDirectory

data class WorkforceReplicaDirectory(
    val checkpoint: WorkforceCheckpoint?,
    val directory: WorkforceDirectory?,
)
