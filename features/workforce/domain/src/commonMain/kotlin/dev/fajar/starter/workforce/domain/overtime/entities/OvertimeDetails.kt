package dev.fajar.starter.workforce.domain.overtime.entities

data class OvertimeDetails(
    val request: OvertimeRequest,
    val approval: OvertimeApproval?,
    val history: OvertimePage<OvertimeChange>,
    val actions: Set<OvertimeAction>,
)

enum class OvertimeAction {
    SUBMIT_ACTUAL,
    WITHDRAW,
    APPROVE,
    REJECT,
}
