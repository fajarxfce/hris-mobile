package dev.fajar.starter.workforce.domain.overtime.entities

data class OvertimeCommandRecord(
    val command: OvertimeCommand,
    val revision: Long,
    val attempts: Int,
    val outcome: OvertimeCommandOutcome,
)
