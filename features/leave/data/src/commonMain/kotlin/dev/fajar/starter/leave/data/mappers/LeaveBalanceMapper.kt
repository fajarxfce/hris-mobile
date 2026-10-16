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
            require(row.typeCode != after)
            row.balance.toBalance(row.typeId, row.typeCode, row.typeName, year)
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
                        row.carry?.toDto(),
                    ),
                )
            },
            nextCursor,
        ),
    )

fun LeaveBalanceDto.toBalance(
    typeId: String,
    typeCode: String,
    typeName: String,
    expectedYear: Int,
): LeaveBalance {
    val id = requireNotNull(accountId)
    require(Uuid.parse(id).toString() == id && Uuid.parse(typeId).toString() == typeId)
    require(Regex("[A-Z][A-Z0-9_-]{0,31}").matches(typeCode))
    require(typeName.isNotBlank() && typeName.length <= 200)
    require(year == expectedYear && year in 1900..2200 && version in 0..9_007_199_254_740_991L)
    val balance =
        LeaveBalance(
            id,
            typeId,
            typeCode,
            typeName,
            year,
            availableDays.toLeaveDays(),
            reservedDays.toLeaveDays(),
            consumedDays.toLeaveDays(),
            closed,
            version,
        )
    return balance.copy(carry = carry?.toCarryBalance(balance))
}
