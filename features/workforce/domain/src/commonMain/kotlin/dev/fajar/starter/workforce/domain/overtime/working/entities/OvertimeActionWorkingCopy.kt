package dev.fajar.starter.workforce.domain.overtime.working.entities

import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeAction
import kotlin.time.Instant

data class OvertimeActionWorkingCopy(
    val requestId: String,
    val employeeId: String,
    val action: OvertimeAction,
    val input: OvertimeActionWorkingInput,
    val revision: String,
    val savedAt: Instant,
) {
    override fun toString() = "OvertimeActionWorkingCopy(redacted)"
}
