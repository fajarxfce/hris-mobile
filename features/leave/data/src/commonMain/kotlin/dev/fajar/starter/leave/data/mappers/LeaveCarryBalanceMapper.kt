@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.leave.data.dto.LeaveCarryBalanceDto
import dev.fajar.starter.leave.domain.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.*

fun LeaveCarryBalanceDto.toCarryBalance(balance: LeaveBalance): LeaveCarryBalance {
    require(Uuid.parse(closingId).toString() == closingId)
    require(version in 0..9_007_199_254_740_991L)
    require(timezone.length in 1..64)
    TimeZone.of(timezone)
    require((validThrough == null) == (expiresAt == null))
    require(validThrough == null || validThrough.length == 10)
    require(expiresAt == null || expiresAt.length in 20..40)
    val date = validThrough?.let(LocalDate::parse)
    require(date == null || (date.year == balance.year && date.plus(1, DateTimeUnit.DAY).day == 1))
    val value =
        LeaveCarryBalance(
            closingId,
            awardedDays.toLeaveDays(),
            availableDays.toLeaveDays(),
            reservedDays.toLeaveDays(),
            consumedDays.toLeaveDays(),
            expiredDays.toLeaveDays(),
            transferredDays.toLeaveDays(),
            deductedDays.toLeaveDays(),
            date,
            expiresAt?.let(Instant::parse),
            timezone,
            version,
        )
    val parts =
        listOf(
            value.available,
            value.reserved,
            value.consumed,
            value.expired,
            value.transferred,
            value.deducted,
        )
    require(value.awarded.halfDays in 1..732)
    require(parts.sumOf { it.halfDays.toLong() } == value.awarded.halfDays.toLong())
    require(
        value.available.halfDays <= balance.available.halfDays &&
            value.reserved.halfDays <= balance.reserved.halfDays &&
            value.consumed.halfDays <= balance.consumed.halfDays
    )
    require(!balance.closed || (value.available.halfDays == 0 && value.reserved.halfDays == 0))
    // Keep the persisted UTC boundary instead of rederiving it from a newer timezone database.
    return value
}

fun LeaveCarryBalance.toDto() =
    LeaveCarryBalanceDto(
        closingId,
        awarded.toWireDays(),
        available.toWireDays(),
        reserved.toWireDays(),
        consumed.toWireDays(),
        expired.toWireDays(),
        transferred.toWireDays(),
        deducted.toWireDays(),
        validThrough?.toString(),
        expiresAt?.toString(),
        timezone,
        version,
    )
