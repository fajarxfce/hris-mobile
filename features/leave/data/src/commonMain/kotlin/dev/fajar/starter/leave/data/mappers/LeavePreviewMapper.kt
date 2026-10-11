@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.leave.data.dto.LeaveRequestPreviewDto
import dev.fajar.starter.leave.domain.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

fun LeaveRequestPreviewDto.toPreview(selection: LeaveDraftSelection): LeaveRequestPreview {
    Uuid.parse(employeeId)
    require(employeeId == selection.employeeId && policy.typeId == selection.typeId)
    require(evaluatedAt.length in 20..40 && timezone.length in 1..100)
    val evaluated = Instant.parse(evaluatedAt)
    TimeZone.of(timezone)
    val requestPolicy = policy.toRequestPolicy()
    require(
        selection.days.size in 1..366 &&
            days.size in 1..selection.days.size &&
            excludedDates.size <= selection.days.size
    )
    val selected = selection.days.associate { it.date to it.portion }
    require(selected.size == selection.days.size)
    val included = days.map { it.toLeaveDay() }
    require(included.zipWithNext().all { (first, next) -> first.date < next.date })
    require(included.all { selected[it.date] == it.portion })
    val excluded =
        excludedDates.map { date ->
            require(date.length == 10)
            LocalDate.parse(date)
        }
    require(excluded.zipWithNext().all { (first, next) -> first < next })
    val includedDates = included.map { it.date }.toSet()
    require(excluded.none { it in includedDates } && includedDates + excluded == selected.keys)
    val charged = chargedDays.toLeaveDays(732)
    require(charged.halfDays == included.sumOf { it.portion.halfDays })
    val chargedYears =
        included
            .groupBy { it.date.year }
            .mapValues { (_, rows) -> rows.sumOf { it.portion.halfDays } }
    require(balances.size in 1..2 && balances.map { it.year } == chargedYears.keys.sorted())
    val proposed =
        balances.map { row ->
            require(row.version in 0..9_007_199_254_740_991L)
            val available = row.availableDays.toLeaveDays()
            val requested = row.requestedDays.toLeaveDays(732)
            val remaining = row.remainingDays.toLeaveDays()
            require(
                requested.halfDays == chargedYears[row.year] &&
                    requested.halfDays <= available.halfDays
            )
            require(remaining.halfDays.toLong() == available.halfDays.toLong() - requested.halfDays)
            LeavePreviewBalance(
                row.year,
                available,
                row.reservedDays.toLeaveDays(),
                row.consumedDays.toLeaveDays(),
                requested,
                remaining,
                row.version,
            )
        }
    return LeaveRequestPreview(
        employeeId,
        evaluated,
        timezone,
        requestPolicy,
        included.toList(),
        charged,
        excluded.toList(),
        proposed.toList(),
    )
}
