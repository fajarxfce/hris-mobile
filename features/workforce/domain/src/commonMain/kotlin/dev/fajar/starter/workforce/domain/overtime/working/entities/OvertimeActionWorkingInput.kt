package dev.fajar.starter.workforce.domain.overtime.working.entities

data class OvertimeActionWorkingInput(
    val requestVersion: Long,
    val actual: OvertimeActualWorkingInput?,
    val reason: String = "",
) {
    override fun toString() = "OvertimeActionWorkingInput(redacted)"
}
