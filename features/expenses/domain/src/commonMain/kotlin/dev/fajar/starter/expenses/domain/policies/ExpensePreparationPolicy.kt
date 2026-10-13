package dev.fajar.starter.expenses.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.companyPolicyDeadline
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

val EXPENSE_PREPARATION_PERIOD = 5.minutes
private val categoryCode = Regex("[A-Z][A-Z0-9_]{1,31}")
private val costCenterCursor = Regex("COST_CENTER:[A-Z0-9][A-Z0-9_-]{1,31}")

fun admitExpenseEditing(access: ExpenseAccess): AppResult<Unit> =
    if (access.permissions.any { it in setOf("expenses.self.manage", "expenses.manage") })
        AppResult.Success(Unit)
    else expenseFailure("access_denied", FailureKind.AccessDenied)

fun validateExpenseCategoryQuery(asOf: LocalDate, after: String?): AppResult<Unit> =
    if (asOf.year !in 1900..2200 || (after != null && !categoryCode.matches(after)))
        expenseFailure("invalid_page")
    else AppResult.Success(Unit)

fun validateExpenseCostCenterQuery(query: String, after: String?): AppResult<Unit> =
    when {
        query.length > 120 -> expenseFailure("invalid_organization_search")
        after != null && !costCenterCursor.matches(after) -> expenseFailure("invalid_page")
        else -> AppResult.Success(Unit)
    }

fun expensePreparationExpiry(
    access: ExpenseAccess,
    policy: CompanyPolicySnapshot,
    requestedAt: Instant,
    now: Instant,
): AppResult<Instant> {
    val bounded = boundExpenseAuthorization(access, policy, now)
    val expiry =
        minOf(
            requestedAt + EXPENSE_PREPARATION_PERIOD,
            bounded.authorizationExpiresAt,
            access.sessionExpiresAt,
            companyPolicyDeadline(policy),
        )
    return if (requestedAt > now || expiry <= now)
        expenseFailure("client.expense_preparation_expired", FailureKind.AccessDenied)
    else AppResult.Success(expiry)
}
