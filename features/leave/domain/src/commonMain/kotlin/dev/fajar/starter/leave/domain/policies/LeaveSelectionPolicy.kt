@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.LeaveSelection
import kotlin.uuid.Uuid

fun leaveFailure(code: String, kind: FailureKind = FailureKind.Validation) =
    AppResult.Failed(Failure(kind, "Leave data could not be loaded.", code = code))

fun validateLeaveSelection(selection: LeaveSelection): AppResult<Unit> =
    when {
        selection.employeeId != null && Uuid.parseOrNull(selection.employeeId) == null ->
            leaveFailure("invalid_employee")
        selection.year != null && selection.year !in 1900..2200 ->
            leaveFailure("invalid_leave_year")
        selection.employmentAfter != null &&
            (selection.employmentAfter.isBlank() || selection.employmentAfter.length > 32) ->
            leaveFailure("invalid_page")
        selection.balanceAfter != null &&
            !Regex("[A-Z][A-Z0-9_-]{0,31}").matches(selection.balanceAfter) ->
            leaveFailure("invalid_page")
        selection.requestAfter != null && Uuid.parseOrNull(selection.requestAfter) == null ->
            leaveFailure("invalid_page")
        else -> AppResult.Success(Unit)
    }

fun leaveFailureInvalidatesCache(failure: Failure): Boolean =
    failure.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied) ||
        failure.code in
            setOf(
                "employee_not_found",
                "leave_request_not_found",
                "company_maintenance",
                "client_update_required",
                "client_version_required",
            )
