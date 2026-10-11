package dev.fajar.starter.leave.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*

const val LEAVE_AUTOMATIC_ATTEMPTS = 3
const val LEAVE_MAXIMUM_ATTEMPTS = 1_000

fun LeaveCommand.belongsTo(access: LeaveAccess) =
    accountId == access.accountId && audience == access.audience && companyId == access.companyId

fun admitLeaveWrite(access: LeaveAccess): AppResult<Unit> =
    if (access.permissions.any { it == "leave.self.manage" || it == "leave.manage" })
        AppResult.Success(Unit)
    else leaveFailure("access_denied", FailureKind.AccessDenied)

fun LeaveRequestDetails.allows(action: LeaveRequestAction): Boolean =
    when (action) {
        LeaveRequestAction.WITHDRAW ->
            status == LeaveStatus.PENDING && LeaveAction.WITHDRAW in availableActions
        LeaveRequestAction.REQUEST_CANCELLATION ->
            status == LeaveStatus.APPROVED && LeaveAction.REQUEST_CANCELLATION in availableActions
        LeaveRequestAction.WITHDRAW_CANCELLATION ->
            status == LeaveStatus.CANCELLATION_PENDING && LeaveAction.WITHDRAW in availableActions
    }

fun validateLeaveActionReason(reason: String): AppResult<Unit> =
    when {
        reason.isBlank() -> leaveFailure("client.leave_reason_required", FailureKind.Validation)
        reason.length > 1_000 ->
            leaveFailure("client.leave_reason_too_long", FailureKind.Validation)
        else -> AppResult.Success(Unit)
    }

/** Later authentication or version rejections cannot disprove an earlier ambiguous commit. */
fun resolveLeaveCommandFailure(
    previous: LeaveCommandOutcome,
    failure: Failure,
): LeaveCommandOutcome =
    if (previous !is LeaveCommandOutcome.Unconfirmed && failure.code in definiteLeaveRejections)
        LeaveCommandOutcome.Rejected(failure)
    else LeaveCommandOutcome.Unconfirmed(failure)

private val definiteLeaveRejections =
    setOf(
        "stale_version",
        "invalid_leave_withdrawal",
        "invalid_leave_cancellation",
        "leave_not_pending",
        "leave_not_approved",
        "leave_request_not_found",
        "leave_year_closed",
        "payroll_period_frozen",
        "approval_unavailable",
        "approval_changed",
        "approval_template_not_found",
        "company_access_denied",
        "sync_access_denied",
        "session_revoked",
        "access_denied",
        "forbidden",
        "mfa_required",
        "mfa_setup_required",
        "recent_authentication_required",
        "mfa_verification_required",
    )
