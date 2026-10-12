@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.overtime.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

const val OVERTIME_AUTOMATIC_ATTEMPTS = 3
const val OVERTIME_MAXIMUM_ATTEMPTS = 1_000

fun OvertimeCommand.belongsTo(access: WorkforceAccess) =
    accountId == access.accountId && audience == access.audience && companyId == access.companyId

fun OvertimeCommand.slot(): OvertimeCommandSlot =
    when (payload) {
        is OvertimeCommandPayload.Plan -> OvertimeCommandSlot.Plan(employeeId)
        else -> OvertimeCommandSlot.Request(requestId)
    }

fun OvertimeCommand.expectedReceiptVersion(): Long =
    when (val input = payload) {
        is OvertimeCommandPayload.Plan -> 0
        is OvertimeCommandPayload.Actual -> input.expectedVersion + 1
        is OvertimeCommandPayload.Withdrawal -> input.expectedVersion + 1
    }

fun validateOvertimeCommandSlot(slot: OvertimeCommandSlot): AppResult<Unit> {
    val id =
        when (slot) {
            is OvertimeCommandSlot.Plan -> slot.employeeId
            is OvertimeCommandSlot.Request -> slot.requestId
        }
    return if (Uuid.parseOrNull(id)?.toString() == id) AppResult.Success(Unit)
    else overtimeFailure("client.overtime_command_changed", FailureKind.Conflict)
}

fun validateOvertimePlanInput(
    prepared: OvertimePreparation,
    access: WorkforceAccess,
    input: OvertimeIntervalInput,
    reason: String,
    now: Instant,
): AppResult<OvertimeCommandPayload.Plan> {
    val review = validateOvertimePreparation(prepared, access, now)
    if (review is AppResult.Failed) return review
    val explanation = validateOvertimeReason(reason)
    if (explanation is AppResult.Failed) return explanation
    if (prepared.review.employee.version !in 0..9_007_199_254_740_991L)
        return overtimeFailure("client.invalid_response", FailureKind.Unexpected)
    return when (val interval = validateOvertimeRequestedInterval(prepared.review, input)) {
        is AppResult.Failed -> interval
        is AppResult.Success ->
            AppResult.Success(
                OvertimeCommandPayload.Plan(
                    prepared.review.employee.version,
                    interval.value,
                    prepared.review.revision,
                )
            )
    }
}

fun admitOvertimeCommandInsertion(admission: OvertimeCommandAdmission): AppResult<Unit> =
    when (admission) {
        OvertimeCommandAdmission.STORED,
        OvertimeCommandAdmission.OCCUPIED -> AppResult.Success(Unit)
        OvertimeCommandAdmission.CAPACITY_EXCEEDED ->
            overtimeFailure("client.command_queue_full", FailureKind.Storage)
        OvertimeCommandAdmission.SCOPE_CHANGED ->
            overtimeFailure("client.overtime_scope_changed", FailureKind.AccessDenied)
    }

fun validateOvertimeAction(
    snapshot: OvertimeSnapshot<OvertimeRequestView>,
    access: WorkforceAccess,
    action: OvertimeRequestedAction,
    reason: String,
    now: Instant,
): AppResult<OvertimeCommandPayload> {
    val allowed = admitSelfOvertimeCommands(access)
    if (allowed is AppResult.Failed) return allowed
    val lease = validateOvertimeCache(access, snapshot, now)
    if (lease is AppResult.Failed) return lease
    val explanation = validateOvertimeReason(reason)
    if (explanation is AppResult.Failed) return explanation
    val details = snapshot.value.details
    val request = details.request
    if (
        request.id != snapshot.value.selection.requestId ||
            request.employeeId != snapshot.value.selection.employeeId
    )
        return overtimeFailure("client.invalid_response", FailureKind.Unexpected)
    if (request.version !in 0..998)
        return overtimeFailure("client.overtime_changed", FailureKind.Conflict)
    return when (action) {
        is OvertimeRequestedAction.Actual -> {
            if (
                request.status != OvertimeStatus.PLANNED ||
                    OvertimeAction.SUBMIT_ACTUAL !in details.actions
            )
                overtimeFailure("overtime_not_planned", FailureKind.Conflict)
            else
                when (
                    val interval = validateOvertimeActualInterval(request, action.interval, now)
                ) {
                    is AppResult.Failed -> interval
                    is AppResult.Success ->
                        AppResult.Success(
                            OvertimeCommandPayload.Actual(request.version, interval.value)
                        )
                }
        }
        OvertimeRequestedAction.Withdraw ->
            if (
                request.status !in setOf(OvertimeStatus.PLANNED, OvertimeStatus.PENDING) ||
                    OvertimeAction.WITHDRAW !in details.actions
            )
                overtimeFailure("overtime_not_pending", FailureKind.Conflict)
            else AppResult.Success(OvertimeCommandPayload.Withdrawal(request.version))
    }
}

/** A later definite-looking rejection cannot disprove an earlier uncertain commit. */
fun resolveOvertimeCommandFailure(
    previous: OvertimeCommandOutcome,
    failure: Failure,
): OvertimeCommandOutcome =
    if (
        previous !is OvertimeCommandOutcome.Unconfirmed &&
            failure.code in definiteOvertimeRejections
    )
        OvertimeCommandOutcome.Rejected(failure)
    else OvertimeCommandOutcome.Unconfirmed(failure)

private val definiteOvertimeRejections =
    setOf(
        "invalid_overtime_request",
        "invalid_overtime_actual",
        "invalid_overtime_withdrawal",
        "invalid_overtime_interval",
        "invalid_overtime_planning_revision",
        "overtime_planning_changed",
        "overtime_date_out_of_range",
        "overtime_employment_inactive",
        "overtime_schedule_missing",
        "overtime_work_date_mismatch",
        "overtime_overlaps_shift",
        "overtime_overlap",
        "overtime_month_capacity",
        "overtime_outside_requested_window",
        "overtime_not_ended",
        "overtime_not_planned",
        "overtime_not_pending",
        "stale_employment_version",
        "stale_version",
        "employee_not_found",
        "overtime_request_not_found",
        "work_period_locked",
        "approval_unavailable",
        "approval_changed",
        "approval_template_not_found",
        "approval_group_too_large",
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
