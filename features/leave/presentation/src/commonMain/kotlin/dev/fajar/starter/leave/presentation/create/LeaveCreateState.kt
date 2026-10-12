package dev.fajar.starter.leave.presentation.create

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.MAX_LEAVE_ATTACHMENTS
import dev.fajar.starter.leave.presentation.evidence.LeaveEvidenceTarget
import kotlinx.datetime.LocalDate

data class LeaveCreateState(
    val form: LeaveCreateForm? = null,
    val catalog: LeavePage<LeaveTypeOption> = LeavePage(emptyList()),
    val catalogAfter: String? = null,
    val preview: LeavePreparation? = null,
    val previewExpired: Boolean = false,
    val command: LeaveSubmissionRecord? = null,
    val commandReady: Boolean = false,
    val work: LeaveCreateWork? = null,
    val failure: Failure? = null,
    val restriction: Failure? = null,
    val dialog: LeaveCreateDialog? = null,
    val evidenceTarget: LeaveEvidenceTarget? = null,
) {
    val editable: Boolean
        get() =
            restriction == null &&
                commandReady &&
                command == null &&
                work !in
                    setOf(
                        LeaveCreateWork.SENDING,
                        LeaveCreateWork.REVIEWING,
                        LeaveCreateWork.DISCARDING,
                    )

    val canPreview: Boolean
        get() = editable && work == null && form?.type != null && evidenceTarget == null

    val canAddAttachment: Boolean
        get() =
            editable &&
                work == null &&
                evidenceTarget == null &&
                (form?.attachments?.size ?: MAX_LEAVE_ATTACHMENTS) < MAX_LEAVE_ATTACHMENTS

    val canSubmit: Boolean
        get() =
            editable &&
                work == null &&
                preview != null &&
                !previewExpired &&
                evidenceTarget == null &&
                (!preview.preview.policy.attachmentRequired ||
                    form?.attachments?.isNotEmpty() == true)

    val visibleFailure: Failure?
        get() = restriction ?: failure

    val needsVerification: Boolean
        get() =
            visibleFailure?.code in
                setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")

    val canRetry: Boolean
        get() =
            restriction == null &&
                commandReady &&
                work == null &&
                (command?.outcome is LeaveCommandOutcome.Pending ||
                    command?.outcome is LeaveCommandOutcome.Unconfirmed)

    val canDiscard: Boolean
        get() =
            restriction == null &&
                commandReady &&
                work == null &&
                (command?.outcome is LeaveCommandOutcome.Pending ||
                    command?.outcome is LeaveCommandOutcome.Rejected)
}

enum class LeaveCreateWork {
    TYPES,
    PREVIEW,
    SENDING,
    REVIEWING,
    DISCARDING,
}

enum class LeaveDateField {
    FROM,
    UNTIL,
}

sealed interface LeaveCreateDialog {
    data object Types : LeaveCreateDialog

    data object Portion : LeaveCreateDialog

    data class Date(val field: LeaveDateField, val month: LocalDate) : LeaveCreateDialog
}

internal fun initialLeaveCreateState(target: LeaveCreateTarget): LeaveCreateState {
    val date = LocalDate.Formats.ISO.parseOrNull(target.asOf)?.takeIf { it.year in 1900..2200 }
    return if (date == null)
        LeaveCreateState(
            failure =
                Failure(
                    FailureKind.Validation,
                    "The selected leave date is invalid.",
                    code = "invalid_leave_request",
                )
        )
    else LeaveCreateState(form = LeaveCreateForm(date, date))
}
