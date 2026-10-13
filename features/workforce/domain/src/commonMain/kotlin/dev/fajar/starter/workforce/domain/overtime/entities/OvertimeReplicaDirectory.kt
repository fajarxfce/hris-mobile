package dev.fajar.starter.workforce.domain.overtime.entities

import dev.fajar.starter.workforce.domain.entities.WorkforceDirectory

data class OvertimeReplicaDirectory(
    val checkpoint: OvertimeCheckpoint?,
    val directory: WorkforceDirectory?,
)
