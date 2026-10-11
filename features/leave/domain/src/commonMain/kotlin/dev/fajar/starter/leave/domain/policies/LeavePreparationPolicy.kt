@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import kotlinx.datetime.daysUntil

val LEAVE_PREPARATION_PERIOD = 5.minutes

fun admitSelfLeavePreparation(access: LeaveAccess): AppResult<Unit> =
    if ("leave.self.manage" in access.permissions) AppResult.Success(Unit)
    else leaveFailure("access_denied", FailureKind.AccessDenied)

fun validateLeaveTypeSelection(selection: LeaveTypeSelection): AppResult<Unit> =
    when {
        selection.asOf.year !in 1900..2200 -> leaveFailure("invalid_leave_year")
        selection.after != null && !Regex("[A-Z][A-Z0-9_-]{0,31}").matches(selection.after) ->
            leaveFailure("invalid_page")
        else -> AppResult.Success(Unit)
    }

fun validateLeaveDraftSelection(selection: LeaveDraftSelection): AppResult<Unit> =
    when {
        Uuid.parseOrNull(selection.employeeId) == null -> leaveFailure("invalid_employee")
        Uuid.parseOrNull(selection.typeId) == null -> leaveFailure("leave_type_unavailable")
        selection.days.size !in 1..366 ||
            selection.days.distinctBy { it.date }.size != selection.days.size ||
            selection.days.any { it.date.year !in 1900..2200 } ->
            leaveFailure("invalid_leave_request")
        selection.days.minOf { it.date }.daysUntil(selection.days.maxOf { it.date }) > 365 ->
            leaveFailure("leave_range_too_large")
        else -> AppResult.Success(Unit)
    }
