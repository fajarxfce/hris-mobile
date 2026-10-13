package dev.fajar.starter.workforce.domain.overtime.entities

data class OvertimeReplicaPage(
    val checkpoint: OvertimeCheckpoint?,
    val requests: OvertimePage<OvertimeSummary>,
)
