@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.mappers

import dev.fajar.starter.workforce.data.dto.*
import dev.fajar.starter.workforce.domain.entities.*
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

fun WorkforceDirectoryDto.toDirectory(access: WorkforceAccess, after: String?): WorkforceDirectory {
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
            WorkforceEmployment(
                it.id,
                it.employeeNumber,
                WorkforceEmploymentStatus.valueOf(it.status),
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
    return WorkforceDirectory(accountId, companyId, date, timezone, items.toList(), cursor)
}

fun WorkforceDirectory.toDto() =
    WorkforceDirectoryDto(
        accountId,
        companyId,
        asOf.toString(),
        timezone,
        WorkforcePageDto(
            items.map {
                WorkforceEmploymentDto(it.id, it.employeeNumber, it.status.name, it.version)
            },
            nextCursor,
        ),
    )

fun WorkforceDirectoryDto.toWorkforceReplicaDirectory(access: WorkforceAccess): WorkforceDirectory {
    require(employments.items.size <= 200 && employments.nextCursor == null)
    val batches = employments.items.chunked(20).ifEmpty { listOf(emptyList()) }
    val directory =
        copy(employments = WorkforcePageDto(batches.first(), null)).toDirectory(access, null)
    val rows =
        batches.flatMap {
            copy(employments = WorkforcePageDto(it, null)).toDirectory(access, null).items
        }
    require(rows.distinctBy { it.id }.size == rows.size)
    require(rows.zipWithNext().all { (first, next) -> first.employeeNumber < next.employeeNumber })
    return directory.copy(items = rows.toList())
}
