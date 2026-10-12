package dev.fajar.starter.workforce.domain.overtime.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.*

/** Resolve company wall times without silently moving a clock gap or choosing a repeated hour. */
fun resolveOvertimeLocalInterval(
    input: OvertimeLocalIntervalInput,
    timezone: String,
    original: OvertimeInterval? = null,
): AppResult<OvertimeIntervalInput> {
    val zone =
        try {
            TimeZone.of(timezone)
        } catch (_: IllegalTimeZoneException) {
            return overtimeFailure("client.invalid_response", FailureKind.Unexpected)
        }
    val startsAt =
        when (val result = resolveOvertimeLocalMinute(input.startsAt, zone, original?.startsAt)) {
            is AppResult.Failed -> return result
            is AppResult.Success -> result.value
        }
    val endsAt =
        when (val result = resolveOvertimeLocalMinute(input.endsAt, zone, original?.endsAt)) {
            is AppResult.Failed -> return result
            is AppResult.Success -> result.value
        }
    return AppResult.Success(OvertimeIntervalInput(startsAt, endsAt, input.breakMinutes))
}

private fun resolveOvertimeLocalMinute(
    value: LocalDateTime,
    zone: TimeZone,
    original: Instant?,
): AppResult<Instant> {
    if (value.year !in 2000..2100 || value.second != 0 || value.nanosecond != 0)
        return overtimeFailure("invalid_overtime_interval")
    if (
        original != null &&
            original.epochSeconds % 60 == 0L &&
            original.nanosecondsOfSecond == 0 &&
            original.toLocalDateTime(zone) == value
    )
        return AppResult.Success(original)
    val wall = value.toInstant(TimeZone.UTC)
    var resolved: Instant? = null
    // The wire contract uses whole UTC minutes. Enumerating every legal minute offset
    // (UTC offsets are bounded to ±18 hours) is exact, bounded, and retains no timezone cache.
    for (offset in -1_080..1_080) {
        val candidate = wall - offset.minutes
        if (zone.offsetAt(candidate).totalSeconds != offset * 60) continue
        if (resolved != null) return overtimeFailure("client.overtime_time_ambiguous")
        resolved = candidate
    }
    return resolved?.let { AppResult.Success(it) } ?: overtimeFailure("client.overtime_time_gap")
}
