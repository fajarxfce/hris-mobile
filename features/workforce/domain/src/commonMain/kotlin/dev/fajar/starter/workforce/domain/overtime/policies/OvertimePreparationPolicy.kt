@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.overtime.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.policies.sameWorkforceScope
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.*

val OVERTIME_PREPARATION_PERIOD = 5.minutes

fun admitSelfOvertimeCommands(access: WorkforceAccess): AppResult<Unit> =
    if (access.permissions.any { it == "overtime.self.manage" || it == "overtime.manage" })
        AppResult.Success(Unit)
    else overtimeFailure("access_denied", FailureKind.AccessDenied)

fun validateOvertimePlanningSelection(selection: OvertimePlanningSelection): AppResult<Unit> =
    when {
        Uuid.parseOrNull(selection.employeeId)?.toString() != selection.employeeId ->
            overtimeFailure("invalid_employee")
        selection.workDate.year !in 2000..2100 -> overtimeFailure("overtime_date_out_of_range")
        selection.employmentAfter != null &&
            (selection.employmentAfter.isBlank() || selection.employmentAfter.length > 32) ->
            overtimeFailure("invalid_page")
        else -> AppResult.Success(Unit)
    }

fun validateOvertimeWorkDate(date: LocalDate, companyDate: LocalDate): AppResult<Unit> =
    if (date.year !in 2000..2100 || companyDate.daysUntil(date) !in -31..90)
        overtimeFailure("overtime_date_out_of_range")
    else AppResult.Success(Unit)

fun validateOvertimePreparation(
    prepared: OvertimePreparation,
    access: WorkforceAccess,
    now: Instant,
): AppResult<Unit> {
    val allowed = admitSelfOvertimeCommands(access)
    if (allowed is AppResult.Failed) return allowed
    if (!sameWorkforceScope(prepared.access, access))
        return overtimeFailure("client.overtime_scope_changed", FailureKind.AccessDenied)
    val selected = validateOvertimePlanningSelection(prepared.selection)
    if (selected is AppResult.Failed) return selected
    if (
        prepared.fetchedAt > now ||
            prepared.expiresAt <= now ||
            prepared.expiresAt > prepared.fetchedAt + OVERTIME_PREPARATION_PERIOD ||
            prepared.expiresAt > prepared.access.authorizationExpiresAt ||
            prepared.expiresAt > prepared.access.sessionExpiresAt ||
            access.authorizationExpiresAt <= now
    )
        return overtimeFailure("client.overtime_review_expired", FailureKind.Conflict)
    val review = prepared.review
    if (
        review.employee.id != prepared.selection.employeeId ||
            review.workDate != prepared.selection.workDate ||
            review.schedule.date != review.workDate ||
            !Regex("[0-9a-f]{64}").matches(review.revision)
    )
        return overtimeFailure("client.invalid_response", FailureKind.Unexpected)
    return validateOvertimeWorkDate(review.workDate, review.companyDate)
}

fun validateOvertimeReason(reason: String): AppResult<Unit> =
    if (reason.isBlank() || reason.length > 1_000) overtimeFailure("invalid_overtime_request")
    else AppResult.Success(Unit)
