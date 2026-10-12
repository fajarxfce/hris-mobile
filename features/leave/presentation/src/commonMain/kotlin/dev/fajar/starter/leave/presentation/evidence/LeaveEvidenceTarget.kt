package dev.fajar.starter.leave.presentation.evidence

/** Each opening owns a distinct target; callbacks from an earlier dialog cannot edit the form. */
class LeaveEvidenceTarget(
    val workspaceId: String,
    val employeeId: String,
    val selectedDocumentIds: Set<String>,
)
