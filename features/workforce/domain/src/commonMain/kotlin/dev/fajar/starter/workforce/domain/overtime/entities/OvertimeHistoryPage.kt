package dev.fajar.starter.workforce.domain.overtime.entities

data class OvertimeHistoryPage(
    val requestId: String,
    val requestVersion: Long,
    val after: Long,
    val history: OvertimePage<OvertimeChange>,
)
