package dev.fajar.starter.expenses.data.mappers

import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import kotlinx.datetime.LocalDate

private val categoryCode = Regex("[A-Z][A-Z0-9_]{1,31}")
private val costCenterCode = Regex("[A-Z0-9][A-Z0-9_-]{1,31}")

fun ExpenseCategoryDto.toCategory(asOf: LocalDate): ExpenseCategory {
    require(
        categoryCode.matches(code) && name.isNotBlank() && name.length <= 120 && currency == "IDR"
    )
    val date = LocalDate.parse(effectiveFrom)
    require(date.year in 1900..2200 && date <= asOf)
    val line = expenseAmount(maximumLineAmount)
    val total = expenseAmount(maximumClaimAmount)
    val lineCents = requireNotNull(expenseMinorUnits(line))
    val totalCents = requireNotNull(expenseMinorUnits(total))
    require(lineCents in 1..99_999_999_999_999L && totalCents in lineCents..99_999_999_999_999L)
    require(maximumAgeDays in 1..366)
    require(
        allowedContracts.size in 1..2 && allowedContracts.distinct().size == allowedContracts.size
    )
    require(expenseVersion(appliedRevision) <= expenseVersion(version))
    return ExpenseCategory(
        expenseId(id),
        code,
        name,
        date,
        line,
        total,
        receiptRequired,
        costCenterRequired,
        maximumAgeDays,
        allowedContracts.map(ExpenseContractKind::valueOf).toSet(),
        active,
        version,
        appliedRevision,
    )
}

fun ExpensePageDto<ExpenseCategoryDto>.toCategories(
    asOf: LocalDate,
    after: String?,
): ExpensePage<ExpenseCategory> {
    require(items.size <= EXPENSE_PAGE_SIZE)
    val mapped = items.map { it.toCategory(asOf) }
    require(
        mapped.distinctBy { it.id }.size == mapped.size &&
            mapped.distinctBy { it.code }.size == mapped.size
    )
    require(after == null || mapped.none { it.code == after })
    require(
        nextCursor == null ||
            (mapped.isNotEmpty() && nextCursor == mapped.last().code && nextCursor != after)
    )
    return ExpensePage(mapped, nextCursor)
}

fun ExpensePageDto<ExpenseCostCenterDto>.toCostCenters(
    after: String?
): ExpensePage<ExpenseCostCenter> {
    require(items.size <= EXPENSE_PAGE_SIZE)
    val mapped =
        items.map {
            require(
                costCenterCode.matches(it.code) && it.name.isNotBlank() && it.name.length <= 200
            )
            ExpenseCostCenter(expenseId(it.id), it.code, it.name, expenseVersion(it.version))
        }
    require(
        mapped.distinctBy { it.id }.size == mapped.size &&
            mapped.distinctBy { it.code }.size == mapped.size
    )
    require(after == null || mapped.none { "COST_CENTER:${it.code}" == after })
    require(
        nextCursor == null ||
            (mapped.isNotEmpty() &&
                nextCursor == "COST_CENTER:${mapped.last().code}" &&
                nextCursor != after)
    )
    return ExpensePage(mapped, nextCursor)
}
