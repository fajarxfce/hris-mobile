package dev.fajar.starter.workforce.domain.overtime.entities

/** The employment page is retained so a detail read can independently verify current ownership. */
data class OvertimeRequestSelection(
    val requestId: String,
    val employeeId: String,
    val employmentAfter: String? = null,
    val historyAfter: Long? = null,
)
