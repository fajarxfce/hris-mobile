package dev.fajar.starter.leave.data

import dev.fajar.starter.leave.data.dto.LeaveCarryBalanceDto

fun carryBalanceDto() =
    LeaveCarryBalanceDto(
        closingId = "77777777-7777-4777-8777-777777777777",
        awardedDays = "4",
        availableDays = "2.5",
        reservedDays = "0.5",
        consumedDays = "0.5",
        expiredDays = "0.5",
        transferredDays = "0",
        deductedDays = "0",
        validThrough = "2026-03-31",
        expiresAt = "2026-03-31T17:00:00Z",
        timezone = "Asia/Jakarta",
        version = 2,
    )
