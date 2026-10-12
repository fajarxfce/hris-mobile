package dev.fajar.starter.workforce.domain.overtime.entities

import dev.fajar.starter.workforce.domain.entities.WorkforceDirectory

data class OvertimeOverview(
    val directory: WorkforceDirectory,
    val selection: OvertimeSelection,
    val requests: OvertimePage<OvertimeSummary>,
)
