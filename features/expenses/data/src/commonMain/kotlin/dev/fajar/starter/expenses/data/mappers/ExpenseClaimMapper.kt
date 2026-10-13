package dev.fajar.starter.expenses.data.mappers

import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.EXPENSE_PAGE_SIZE
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

fun ExpenseSummaryDto.toSummary(): ExpenseSummary {
    require(employeeNumber.isNotBlank() && employeeNumber.length <= 32)
    require(employeeName.isNotBlank() && employeeName.length <= 200)
    require(title.isNotBlank() && title.length <= 160 && currency == "IDR")
    return ExpenseSummary(
        expenseId(id),
        expenseId(employmentId),
        employeeNumber,
        employeeName,
        title,
        expenseAmount(totalAmount),
        currency,
        Instant.parse(createdAt),
        ExpenseStatus.valueOf(status),
        expenseVersion(version),
    )
}

fun ExpensePageDto<ExpenseSummaryDto>.toClaims(
    selection: ExpenseSelection
): ExpensePage<ExpenseSummary> {
    require(items.size <= EXPENSE_PAGE_SIZE)
    val mapped = items.map { it.toSummary() }
    require(mapped.distinctBy { it.id }.size == mapped.size)
    require(
        mapped.zipWithNext().all { (first, second) ->
            first.createdAt > second.createdAt ||
                (first.createdAt == second.createdAt && first.id > second.id)
        }
    )
    if (selection.after != null) require(mapped.none { it.id == selection.after })
    require(
        nextCursor == null ||
            (mapped.isNotEmpty() && nextCursor == mapped.last().id && nextCursor != selection.after)
    )
    return ExpensePage(mapped.toList(), nextCursor)
}

fun ExpenseLineDto.toLine(): ExpenseLine {
    require(description.isNotBlank() && description.length <= 500)
    require(
        receiptRevisionIds.size <= 3 &&
            receiptRevisionIds.distinct().size == receiptRevisionIds.size
    )
    val date = LocalDate.parse(occurredOn)
    require(date.year in 1900..2200)
    return ExpenseLine(
        expenseId(id),
        expenseId(categoryId),
        date,
        expenseAmount(amount),
        description,
        costCenterId?.let(::expenseId),
        receiptRevisionIds.map(::expenseId),
    )
}

fun ExpenseDraftDto.toDraft(): ExpenseDraft {
    require(revision in 0..99 && lines.size <= 20 && currency == "IDR")
    require(employeeNumber.isNotBlank() && employeeNumber.length <= 32)
    require(employeeName.isNotBlank() && employeeName.length <= 200)
    require(
        title.isNotBlank() &&
            title.length <= 160 &&
            description.length <= 2000 &&
            reason.length <= 1000
    )
    val mapped = lines.map { it.toLine() }
    require(mapped.distinctBy { it.id }.size == mapped.size)
    return ExpenseDraft(
        revision,
        employeeNumber,
        employeeName,
        title,
        description,
        expenseAmount(totalAmount),
        currency,
        mapped.toList(),
        expenseId(actorId),
        reason,
        Instant.parse(recordedAt),
    )
}

fun ExpenseClaimDto.toClaim(expectedId: String): ExpenseClaim {
    require(
        expenseId(id) == expectedId && draftRevision == draft.revision && submissionCount in 0..20
    )
    if (latestSubmissionId != null) require(submissionCount > 0)
    return ExpenseClaim(
        id,
        expenseId(employmentId),
        expenseId(createdBy),
        Instant.parse(createdAt),
        expenseVersion(version),
        ExpenseStatus.valueOf(status),
        draft.toDraft(),
        submissionCount,
        latestSubmissionId?.let(::expenseId),
    )
}

fun ExpenseChangeDto.toChange(): ExpenseChange {
    require(draftRevision in 0..99 && reason.length <= 1000)
    return ExpenseChange(
        expenseVersion(version),
        draftRevision,
        ExpenseStatus.valueOf(status),
        ExpenseChangeKind.valueOf(kind),
        expenseId(actorId),
        reason,
        Instant.parse(recordedAt),
        submissionId?.let(::expenseId),
    )
}

fun ExpensePageDto<ExpenseChangeDto>.toHistory(after: Long?): ExpensePage<ExpenseChange> {
    require(items.size <= EXPENSE_PAGE_SIZE)
    val mapped = items.map { it.toChange() }
    require(mapped.zipWithNext().all { (first, second) -> first.version > second.version })
    if (after != null) require(mapped.all { it.version < after })
    require(
        nextCursor == null ||
            (mapped.isNotEmpty() &&
                nextCursor == mapped.last().version.toString() &&
                nextCursor != after?.toString())
    )
    return ExpensePage(mapped.toList(), nextCursor)
}
