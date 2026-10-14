package dev.fajar.starter.expenses.data.replica.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.data.encoding.EXPENSE_CACHE_MAXIMUM_BYTES
import dev.fajar.starter.expenses.data.mappers.*
import dev.fajar.starter.expenses.data.replica.dto.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.entities.*
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

fun ExpenseDirectoryDto.toReplicaDirectory(access: ExpenseAccess): ExpenseDirectory {
    require(employments.nextCursor == null && employments.items.size <= 200)
    val chunks = employments.items.chunked(EXPENSE_PAGE_SIZE).ifEmpty { listOf(emptyList()) }
    val mapped =
        chunks.map { copy(employments = ExpensePageDto(it, null)).toDirectory(access, null) }
    val items = mapped.flatMap { it.items }
    require(
        items.distinctBy { it.id }.size == items.size &&
            items.zipWithNext().all { (a, b) -> a.employeeNumber < b.employeeNumber }
    )
    return mapped.first().copy(items = items.toList(), nextCursor = null)
}

fun ExpenseClaimRecord.toSummary() =
    ExpenseSummary(
        claim.id,
        claim.employeeId,
        claim.draft.employeeNumber,
        claim.draft.employeeName,
        claim.draft.title,
        claim.draft.total,
        claim.draft.currency,
        claim.createdAt,
        claim.status,
        claim.version,
    )

fun ExpenseSnapshot<ExpenseClaimRecord>.toHeader(size: Int) =
    ExpenseClaimHeaderDto(
        value.claim.id,
        value.claim.version,
        value.claim.employeeId,
        generation,
        access.sessionId,
        access.permissions.toSet(),
        access.sessionExpiresAt.toString(),
        access.timezone,
        access.authorizationExpiresAt.toString(),
        access.authorizationExpiryCode,
        fetchedAt.toString(),
        refreshAfter.toString(),
        size,
        value.history.nextCursor?.toLong(),
    )

fun ExpenseClaimHeaderDto.validateHeader() {
    expenseId(claimId)
    expenseId(employeeId)
    expenseId(acquisitionId)
    expenseVersion(claimVersion)
    require(
        sessionId.length in 1..128 &&
            permissions.size <= 256 &&
            permissions.all { it.length in 1..128 }
    )
    require(timezone.length in 1..100)
    TimeZone.of(timezone)
    Instant.parse(sessionExpiresAt)
    Instant.parse(authorizationExpiresAt)
    require(authorizationExpiryCode.length in 1..128)
    val fetched = Instant.parse(fetchedAt)
    val refresh = Instant.parse(refreshAfter)
    require(fetched < refresh && refresh <= fetched + EXPENSE_FRESHNESS_PERIOD)
    require(size in 1..EXPENSE_CACHE_MAXIMUM_BYTES)
    require(pendingHistoryAfter == null || pendingHistoryAfter in 0..claimVersion)
}

fun ExpenseClaimHeaderDto.toSnapshot(
    access: ExpenseAccess,
    record: ExpenseClaimRecord,
): ExpenseSnapshot<ExpenseClaimRecord> {
    validateHeader()
    require(
        record.claim.id == claimId &&
            record.claim.version == claimVersion &&
            record.claim.employeeId == employeeId &&
            record.historyAfter == null
    )
    require(validateExpenseClaimRecord(record) is AppResult.Success)
    return ExpenseSnapshot(
        access.copy(
            sessionId = sessionId,
            permissions = permissions.toSet(),
            sessionExpiresAt = Instant.parse(sessionExpiresAt),
            timezone = timezone,
            authorizationExpiresAt = Instant.parse(authorizationExpiresAt),
            authorizationExpiryCode = authorizationExpiryCode,
        ),
        acquisitionId,
        record,
        Instant.parse(fetchedAt),
        Instant.parse(refreshAfter),
    )
}

fun ExpenseHistoryPage.toDto() =
    ExpenseHistoryPageDto(
        claimId,
        claimVersion,
        after,
        ExpensePageDto(history.items.map { it.toDto() }, history.nextCursor),
    )

fun ExpenseHistoryPageDto.toHistoryPage(): ExpenseHistoryPage {
    expenseId(claimId)
    expenseVersion(claimVersion)
    require(after in 0..claimVersion && validateExpenseHistoryCursor(after) is AppResult.Success)
    return ExpenseHistoryPage(claimId, claimVersion, after, history.toHistory(after))
}
