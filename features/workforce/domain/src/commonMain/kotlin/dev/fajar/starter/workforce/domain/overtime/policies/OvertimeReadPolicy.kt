@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.overtime.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.CalendarPeriod
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlin.uuid.Uuid
import kotlinx.datetime.*

fun overtimeFailure(code: String, kind: FailureKind = FailureKind.Validation) =
    AppResult.Failed(Failure(kind, "Overtime could not be completed.", code = code))

fun overtimeFailureInvalidatesCache(failure: Failure): Boolean =
    failure.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied) ||
        failure.code in
            setOf(
                "employee_not_found",
                "overtime_request_not_found",
                "company_maintenance",
                "client_update_required",
                "client_version_required",
                "sync_scope_changed",
            )

fun overtimeMonth(date: LocalDate): CalendarPeriod {
    val start = LocalDate(date.year, date.month, 1)
    return CalendarPeriod(start, start.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY))
}

fun validateOvertimeSelection(selection: OvertimeSelection): AppResult<Unit> =
    when {
        selection.employeeId != null &&
            Uuid.parseOrNull(selection.employeeId)?.toString() != selection.employeeId ->
            overtimeFailure("invalid_employee")
        selection.period != null &&
            (selection.period.from.year !in 2000..2100 ||
                selection.period.until.year !in 2000..2100 ||
                selection.period.from.daysUntil(selection.period.until) !in 0..61) ->
            overtimeFailure("invalid_overtime_page")
        selection.employmentAfter != null &&
            (selection.employmentAfter.isBlank() || selection.employmentAfter.length > 32) ->
            overtimeFailure("invalid_page")
        selection.requestAfter != null &&
            Uuid.parseOrNull(selection.requestAfter)?.toString() != selection.requestAfter &&
            !isOvertimeReplicaCursor(selection.requestAfter, selection.period) ->
            overtimeFailure("invalid_overtime_page")
        else -> AppResult.Success(Unit)
    }

/** Local request order is company work date followed by resource ID. */
fun isOvertimeReplicaCursor(value: String, period: CalendarPeriod? = null): Boolean {
    val parts = value.split('/')
    if (parts.size != 3 || parts[0] != "r" || Uuid.parseOrNull(parts[2])?.toString() != parts[2])
        return false
    val date = runCatching { LocalDate.parse(parts[1]) }.getOrNull() ?: return false
    return date.toString() == parts[1] &&
        date.year in 2000..2100 &&
        (period == null || date in period.from..period.until)
}

fun validateOvertimeRequestSelection(selection: OvertimeRequestSelection): AppResult<Unit> =
    when {
        Uuid.parseOrNull(selection.requestId)?.toString() != selection.requestId ||
            Uuid.parseOrNull(selection.employeeId)?.toString() != selection.employeeId ->
            overtimeFailure("overtime_request_not_found")
        selection.employmentAfter != null &&
            (selection.employmentAfter.isBlank() || selection.employmentAfter.length > 32) ->
            overtimeFailure("invalid_page")
        selection.historyAfter != null && selection.historyAfter !in 0..999 ->
            overtimeFailure("invalid_overtime_page")
        else -> AppResult.Success(Unit)
    }

fun OvertimeRequest.toSummary() =
    OvertimeSummary(
        id,
        employeeId,
        workDate,
        timezone,
        requested,
        actual?.workedMinutes,
        approvedMinutes,
        status,
        version,
    )
