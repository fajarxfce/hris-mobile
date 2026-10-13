package dev.fajar.starter.expenses.data.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import kotlin.time.Instant
import kotlinx.datetime.*

fun ExpenseOverview.toDto() =
    ExpenseOverviewDto(
        directory.toDto(),
        selection.employeeId,
        requireNotNull(selection.month).toString(),
        selection.employmentAfter,
        selection.status?.name,
        selection.after,
        ExpensePageDto(page.items.map { it.toDto() }, page.nextCursor),
    )

fun ExpenseOverviewDto.toOverview(access: ExpenseAccess): ExpenseOverview {
    val selection =
        ExpenseSelection(
            employeeId,
            YearMonth.parse(month),
            employmentAfter,
            status?.let(ExpenseStatus::valueOf),
            after,
        )
    require(validateExpenseSelection(selection) is AppResult.Success)
    val owned = directory.toDirectory(access, employmentAfter)
    require(employeeId == null || owned.items.any { it.id == employeeId })
    val claims = page.toClaims(selection)
    require(
        claims.items.all {
            val date = it.createdAt.toLocalDateTime(TimeZone.of(owned.timezone))
            it.employeeId == employeeId &&
                YearMonth(date.year, date.month) == selection.month &&
                (selection.status == null || selection.status == it.status)
        }
    )
    return ExpenseOverview(owned, selection, claims)
}

fun ExpenseView.toDto() =
    ExpenseViewDto(
        claim.toDto(),
        submission?.toDto(),
        ExpensePageDto(history.items.map { it.toDto() }, history.nextCursor),
        historyAfter,
        payments.map { it.toDto() },
    )

fun ExpenseViewDto.toView(): ExpenseView {
    require(validateExpenseHistoryCursor(historyAfter) is AppResult.Success)
    val details = claim.toClaim(claim.id)
    val submitted = submission?.toSubmission(requireNotNull(details.latestSubmissionId))
    val view =
        ExpenseView(
            details,
            submitted,
            history.toHistory(historyAfter),
            historyAfter,
            payments.toPayments(),
        )
    require(validateExpenseView(view) is AppResult.Success)
    return view
}

fun <T, D> ExpenseSnapshot<T>.toDto(mapValue: (T) -> D) =
    ExpenseSnapshotDto(
        access.permissions.toSet(),
        access.sessionExpiresAt.toString(),
        access.timezone,
        fetchedAt.toString(),
        refreshAfter.toString(),
        mapValue(value),
    )

fun <T, D> ExpenseSnapshotDto<D>.toSnapshot(
    access: ExpenseAccess,
    generation: String,
    mapValue: (D) -> T,
): ExpenseSnapshot<T> {
    require(permissions.size <= 256 && permissions.all { it.length in 1..128 })
    require(timezone.length in 1..100)
    TimeZone.of(timezone)
    val owner =
        access.copy(
            permissions = permissions.toSet(),
            sessionExpiresAt = Instant.parse(sessionExpiresAt),
            timezone = timezone,
        )
    val fetched = Instant.parse(fetchedAt)
    val expiry = Instant.parse(expiresAt)
    require(expiry > fetched && expiry <= fetched + EXPENSE_FRESHNESS_PERIOD)
    return ExpenseSnapshot(owner, generation, mapValue(value), fetched, expiry)
}
