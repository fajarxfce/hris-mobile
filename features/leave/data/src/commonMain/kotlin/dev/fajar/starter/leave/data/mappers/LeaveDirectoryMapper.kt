@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.domain.entities.*
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

fun LeaveDirectoryDto.toDirectory(access: LeaveAccess, after: String?): LeaveDirectory {
    Uuid.parse(accountId)
    Uuid.parse(companyId)
    require(accountId == access.accountId && companyId == access.companyId)
    require(employments.items.size <= 20 && timezone.length in 1..100)
    TimeZone.of(timezone)
    val date = LocalDate.parse(asOf)
    require(date.year in 1900..9998)
    val items =
        employments.items.map {
            Uuid.parse(it.id)
            require(
                it.employeeNumber.isNotBlank() &&
                    it.employeeNumber.length <= 32 &&
                    it.version in 0..9_007_199_254_740_991L
            )
            LeaveEmployment(
                it.id,
                it.employeeNumber,
                LeaveEmploymentStatus.valueOf(it.status),
                it.version,
            )
        }
    require(
        items.distinctBy { it.id }.size == items.size &&
            items.distinctBy { it.employeeNumber }.size == items.size
    )
    val cursor = employments.nextCursor
    require(
        cursor == null ||
            (cursor.length in 1..32 &&
                items.isNotEmpty() &&
                cursor == items.last().employeeNumber &&
                cursor != after)
    )
    return LeaveDirectory(accountId, companyId, date, timezone, items.toList(), cursor)
}

fun LeaveDirectory.toDto() =
    LeaveDirectoryDto(
        accountId,
        companyId,
        asOf.toString(),
        timezone,
        LeavePageDto(
            items.map { LeaveEmploymentDto(it.id, it.employeeNumber, it.status.name, it.version) },
            nextCursor,
        ),
    )
