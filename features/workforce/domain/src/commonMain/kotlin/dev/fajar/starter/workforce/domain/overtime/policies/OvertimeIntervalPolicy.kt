package dev.fajar.starter.workforce.domain.overtime.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.WorkDay
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlin.time.Instant
import kotlinx.datetime.*

fun calculateOvertimeInterval(input: OvertimeIntervalInput): AppResult<OvertimeInterval> {
    if (
        input.startsAt.nanosecondsOfSecond != 0 ||
            input.endsAt.nanosecondsOfSecond != 0 ||
            input.startsAt.epochSeconds % 60 != 0L ||
            input.endsAt.epochSeconds % 60 != 0L ||
            input.endsAt <= input.startsAt
    )
        return overtimeFailure("invalid_overtime_interval")
    val elapsed = (input.endsAt - input.startsAt).inWholeMinutes
    if (elapsed !in 1..720 || input.breakMinutes !in 0..120 || input.breakMinutes >= elapsed)
        return overtimeFailure("invalid_overtime_interval")
    return AppResult.Success(
        OvertimeInterval(
            input.startsAt,
            input.endsAt,
            input.breakMinutes,
            elapsed.toInt() - input.breakMinutes,
        )
    )
}

fun validateOvertimeRequestedInterval(
    review: OvertimePlanningReview,
    input: OvertimeIntervalInput,
): AppResult<OvertimeInterval> {
    val interval =
        when (val value = calculateOvertimeInterval(input)) {
            is AppResult.Failed -> return value
            is AppResult.Success -> value.value
        }
    val date = review.workDate
    val schedule = review.schedule
    if (schedule.date != date || schedule is WorkDay.Unassigned)
        return overtimeFailure("overtime_schedule_missing")
    val zone =
        try {
            TimeZone.of(review.timezone)
        } catch (_: IllegalArgumentException) {
            return overtimeFailure("client.invalid_response", FailureKind.Unexpected)
        }
    if (
        interval.startsAt < date.atStartOfDayIn(zone) ||
            interval.endsAt > date.plus(2, DateTimeUnit.DAY).atStartOfDayIn(zone)
    )
        return overtimeFailure("overtime_work_date_mismatch")
    if (
        schedule is WorkDay.Work &&
            interval.startsAt < schedule.endsAt &&
            interval.endsAt > schedule.startsAt
    )
        return overtimeFailure("overtime_overlaps_shift")
    if (
        schedule is WorkDay.Off &&
            interval.startsAt >= date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
    )
        return overtimeFailure("overtime_work_date_mismatch")
    return AppResult.Success(interval)
}

fun validateOvertimeActualInterval(
    request: OvertimeRequest,
    input: OvertimeIntervalInput,
    now: Instant,
): AppResult<OvertimeInterval> {
    val actual =
        when (val value = calculateOvertimeInterval(input)) {
            is AppResult.Failed -> return value
            is AppResult.Success -> value.value
        }
    if (
        actual.startsAt < request.requested.startsAt ||
            actual.endsAt > request.requested.endsAt ||
            actual.workedMinutes > request.requested.workedMinutes
    )
        return overtimeFailure("overtime_outside_requested_window")
    if (actual.endsAt > now) return overtimeFailure("overtime_not_ended")
    return AppResult.Success(actual)
}
