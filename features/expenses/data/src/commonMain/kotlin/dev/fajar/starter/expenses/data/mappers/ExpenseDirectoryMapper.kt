package dev.fajar.starter.expenses.data.mappers

import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.EXPENSE_PAGE_SIZE
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

fun ExpenseDirectoryDto.toDirectory(access: ExpenseAccess, after: String?): ExpenseDirectory {
    require(expenseId(accountId) == access.accountId && expenseId(companyId) == access.companyId)
    require(employments.items.size <= EXPENSE_PAGE_SIZE && timezone.length in 1..100)
    TimeZone.of(timezone)
    val date = LocalDate.parse(asOf)
    require(date.year in 1900..2200)
    val items =
        employments.items.map {
            require(it.employeeNumber.isNotBlank() && it.employeeNumber.length <= 32)
            ExpenseEmployment(
                expenseId(it.id),
                it.employeeNumber,
                ExpenseEmploymentStatus.valueOf(it.status),
                expenseVersion(it.version),
            )
        }
    require(
        items.distinctBy { it.id }.size == items.size &&
            items.distinctBy { it.employeeNumber }.size == items.size
    )
    val next = employments.nextCursor
    require(
        next == null || (items.isNotEmpty() && next == items.last().employeeNumber && next != after)
    )
    return ExpenseDirectory(accountId, companyId, date, timezone, items.toList(), next)
}

fun ExpenseDirectory.toDto() =
    ExpenseDirectoryDto(
        accountId,
        companyId,
        asOf.toString(),
        timezone,
        ExpensePageDto(
            items.map {
                ExpenseEmploymentDto(it.id, it.employeeNumber, it.status.name, it.version)
            },
            nextCursor,
        ),
    )
