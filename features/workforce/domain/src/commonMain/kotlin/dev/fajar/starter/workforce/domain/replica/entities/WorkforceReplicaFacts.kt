package dev.fajar.starter.workforce.domain.replica.entities

/** One indexed read uses a single published generation and revision for every source. */
data class WorkforceReplicaFacts(
    val checkpoint: WorkforceCheckpoint?,
    val attendance: List<WorkforceRecord.Attendance>,
    val schedules: List<WorkforceRecord.Schedule>,
    val roster: List<WorkforceRecord.Roster>,
    val holidays: List<WorkforceRecord.Holiday>,
)
