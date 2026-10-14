package dev.fajar.starter.expenses.data.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*

fun ExpenseClaimRecordDto.toRecord(id: String, after: Long?): ExpenseClaimRecord {
    require(validateExpenseHistoryCursor(after) is AppResult.Success)
    val current = claim.toClaim(id)
    require(after == null || after <= current.version)
    return ExpenseClaimRecord(
            current,
            submission?.toSubmission(requireNotNull(current.latestSubmissionId)),
            history.toHistory(after),
            after,
        )
        .also { require(validateExpenseClaimRecord(it) is AppResult.Success) }
}

fun ExpenseClaimRecord.toDto() =
    ExpenseClaimRecordDto(
        claim.toDto(),
        submission?.toDto(),
        ExpensePageDto(history.items.map { it.toDto() }, history.nextCursor),
    )
