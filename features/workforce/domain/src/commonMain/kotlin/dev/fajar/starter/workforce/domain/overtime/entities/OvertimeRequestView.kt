package dev.fajar.starter.workforce.domain.overtime.entities

data class OvertimeRequestView(
    val selection: OvertimeRequestSelection,
    val details: OvertimeDetails,
)
