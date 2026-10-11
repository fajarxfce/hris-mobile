@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.domain.entities.*
import kotlin.uuid.Uuid

fun LeaveBalancesDto.toBalances(
    employeeId: String,
    expectedYear: Int,
    after: String?,
): LeavePage<LeaveBalance> {
    Uuid.parse(employee.id)
    require(
        employee.id == employeeId &&
            year == expectedYear &&
            year in 1900..2200 &&
            balances.items.size <= 20
    )
    val items =
        balances.items.map { row ->
            val raw = row.balance
            Uuid.parse(row.typeId)
            Uuid.parse(requireNotNull(raw.accountId))
            require(
                Regex("[A-Z][A-Z0-9_-]{0,31}").matches(row.typeCode) &&
                    row.typeName.isNotBlank() &&
                    row.typeName.length <= 200
            )
            require(
                raw.year == year &&
                    raw.version in 0..9_007_199_254_740_991L &&
                    row.typeCode != after
            )
            LeaveBalance(
                raw.accountId,
                row.typeId,
                row.typeCode,
                row.typeName,
                year,
                raw.availableDays.toLeaveDays(),
                raw.reservedDays.toLeaveDays(),
                raw.consumedDays.toLeaveDays(),
                raw.closed,
                raw.version,
            )
        }
    require(
        items.distinctBy { it.typeId }.size == items.size &&
            items.distinctBy { it.typeCode }.size == items.size &&
            items.distinctBy { it.accountId }.size == items.size
    )
    require(items.zipWithNext().all { (first, second) -> first.typeCode < second.typeCode })
    val cursor = balances.nextCursor
    require(
        cursor == null || (items.size == 20 && cursor == items.last().typeCode && cursor != after)
    )
    return LeavePage(items.toList(), cursor)
}

fun LeavePage<LeaveBalance>.toBalancesDto(employeeId: String, year: Int) =
    LeaveBalancesDto(
        LeaveEmployeeReferenceDto(employeeId),
        year,
        LeavePageDto(
            items.map { row ->
                LeaveBalanceSummaryDto(
                    row.typeId,
                    row.typeCode,
                    row.typeName,
                    LeaveBalanceDto(
                        row.year,
                        row.available.toWireDays(),
                        row.reserved.toWireDays(),
                        row.consumed.toWireDays(),
                        row.version,
                        row.closed,
                        row.accountId,
                    ),
                )
            },
            nextCursor,
        ),
    )
