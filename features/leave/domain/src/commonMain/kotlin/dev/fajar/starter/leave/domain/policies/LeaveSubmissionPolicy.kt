@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun LeaveSubmissionCommand.belongsTo(access: LeaveAccess): Boolean =
    accountId == access.accountId && audience == access.audience && companyId == access.companyId

fun validateLeaveSubmission(
    preparation: LeavePreparation,
    access: LeaveAccess,
    reason: String,
    attachmentRevisionIds: List<String>,
    now: Instant,
): AppResult<Unit> {
    val permitted = admitSelfLeavePreparation(access)
    if (permitted is AppResult.Failed) return permitted
    val dates = validateLeaveDraftSelection(preparation.selection)
    if (dates is AppResult.Failed) return dates
    if (!sameLeaveScope(preparation.access, access))
        return leaveFailure("client.leave_scope_changed", FailureKind.AccessDenied)
    if (
        preparation.fetchedAt > now ||
            preparation.expiresAt <= now ||
            preparation.expiresAt > preparation.fetchedAt + LEAVE_PREPARATION_PERIOD
    )
        return leaveFailure("client.leave_preview_expired", FailureKind.Conflict)
    if (
        preparation.preview.employeeId != preparation.selection.employeeId ||
            preparation.preview.policy.typeId != preparation.selection.typeId ||
            !Regex("[0-9a-f]{64}").matches(preparation.preview.revision)
    )
        return leaveFailure("client.invalid_response", FailureKind.Unexpected)
    val reasonValidation = validateLeaveActionReason(reason)
    if (reasonValidation is AppResult.Failed) return reasonValidation
    if (
        attachmentRevisionIds.size > 3 ||
            attachmentRevisionIds.distinct().size != attachmentRevisionIds.size ||
            attachmentRevisionIds.any { Uuid.parseOrNull(it)?.toString() != it }
    )
        return leaveFailure("invalid_leave_attachments")
    if (preparation.preview.policy.attachmentRequired && attachmentRevisionIds.isEmpty())
        return leaveFailure("leave_attachment_required")
    return AppResult.Success(Unit)
}

/** A later rejection cannot disprove an earlier uncertain commit of the same operation. */
fun resolveLeaveSubmissionFailure(
    previous: LeaveCommandOutcome,
    failure: Failure,
): LeaveCommandOutcome =
    if (
        previous !is LeaveCommandOutcome.Unconfirmed && failure.code in definiteSubmissionRejections
    )
        LeaveCommandOutcome.Rejected(failure)
    else LeaveCommandOutcome.Unconfirmed(failure)

private val definiteSubmissionRejections =
    setOf(
        "invalid_leave_preview",
        "leave_preview_changed",
        "invalid_leave_request",
        "leave_range_too_large",
        "leave_type_unavailable",
        "invalid_leave_attachments",
        "leave_attachment_required",
        "leave_attachment_unavailable",
        "employee_not_found",
        "leave_employee_ineligible",
        "leave_schedule_missing",
        "no_leave_working_days",
        "partial_leave_unavailable",
        "leave_duration_too_short",
        "leave_request_limit",
        "leave_overlap",
        "insufficient_leave_balance",
        "leave_balance_limit",
        "leave_year_closed",
        "payroll_period_frozen",
        "approval_unavailable",
        "approval_changed",
        "approval_template_not_found",
        "company_access_denied",
        "company_not_found",
        "sync_access_denied",
        "session_revoked",
        "access_denied",
        "forbidden",
        "mfa_required",
        "mfa_setup_required",
        "recent_authentication_required",
        "mfa_verification_required",
    )
