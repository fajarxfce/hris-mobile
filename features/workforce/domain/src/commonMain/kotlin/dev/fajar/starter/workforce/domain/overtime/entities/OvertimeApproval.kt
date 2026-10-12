package dev.fajar.starter.workforce.domain.overtime.entities

/** Current progress; the employee view does not retain the approver account directory. */
data class OvertimeApproval(
    val id: String,
    val status: OvertimeApprovalStatus,
    val currentStep: Int,
    val stageCount: Int,
    val version: Long,
)

enum class OvertimeApprovalStatus {
    PENDING,
    BLOCKED,
    APPROVED,
    REJECTED,
    CANCELLED,
}
