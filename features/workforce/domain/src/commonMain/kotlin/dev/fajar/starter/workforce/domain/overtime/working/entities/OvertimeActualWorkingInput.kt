package dev.fajar.starter.workforce.domain.overtime.working.entities

import kotlinx.datetime.LocalDateTime

data class OvertimeActualWorkingInput(
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
    val breakMinutes: String,
) {
    override fun toString() = "OvertimeActualWorkingInput(redacted)"
}
