@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.*
import kotlin.uuid.Uuid
import kotlinx.datetime.*

fun calendarFailure(code: String, kind: FailureKind = FailureKind.Validation) =
    AppResult.Failed(Failure(kind, "The work calendar could not be loaded.", code = code))

fun validateCalendarSelection(selection: WorkCalendarSelection): AppResult<Unit> =
    when {
        selection.after != null && (selection.after.isBlank() || selection.after.length > 32) ->
            calendarFailure("invalid_page")
        selection.employeeId != null && Uuid.parseOrNull(selection.employeeId) == null ->
            calendarFailure("invalid_employee")
        selection.from != null && selection.from.year !in 1900..9998 ->
            calendarFailure("invalid_calendar_range")
        else -> AppResult.Success(Unit)
    }

fun calendarWeekStart(date: LocalDate): LocalDate =
    date.minus(date.dayOfWeek.ordinal, DateTimeUnit.DAY)

/** Week navigation is bounded to a year either side of the server's company date. */
fun calendarPeriod(today: LocalDate, requestedFrom: LocalDate?): AppResult<CalendarPeriod> {
    val start = requestedFrom ?: calendarWeekStart(today)
    if (today.daysUntil(start) !in -366..366 || start.dayOfWeek != DayOfWeek.MONDAY)
        return calendarFailure("invalid_calendar_range")
    return AppResult.Success(CalendarPeriod(start, start.plus(6, DateTimeUnit.DAY)))
}

/**
 * Only authoritative access/resource loss invalidates saved content; transport failure does not.
 */
fun calendarFailureInvalidatesCache(failure: Failure): Boolean =
    failure.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied) ||
        failure.code in
            setOf(
                "employee_not_found",
                "company_maintenance",
                "client_update_required",
                "client_version_required",
            )
