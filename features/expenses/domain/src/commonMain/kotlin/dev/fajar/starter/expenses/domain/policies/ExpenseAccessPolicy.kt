package dev.fajar.starter.expenses.domain.policies

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

val EXPENSE_FRESHNESS_PERIOD = 1.hours

fun admitExpenses(
    workspaceId: String,
    workspace: Workspace?,
    session: Session?,
    build: AppBuild,
    now: Instant,
): AppResult<ExpenseAccess> {
    val ownership = validateLocalWorkspaceOwnership(workspaceId, workspace, session)
    if (ownership is AppResult.Failed) return ownership
    requireNotNull(workspace)
    requireNotNull(session)
    val module = requireCompanyModule(workspace, CompanyModule.EXPENSES)
    if (module is AppResult.Failed) return module
    when (companyAvailability(workspace.policy, build, now)) {
        CompanyAvailability.Unknown ->
            return companyPolicyFailure(
                "client.company_policy_unavailable",
                FailureKind.Unavailable,
            )
        CompanyAvailability.ClientMetadataRequired ->
            return companyPolicyFailure("client_version_required")
        is CompanyAvailability.UpdateRequired ->
            return companyPolicyFailure("client_update_required")
        is CompanyAvailability.Maintenance ->
            return companyPolicyFailure("company_maintenance", FailureKind.Service)
        CompanyAvailability.Available -> Unit
    }
    if (
        workspace.permissions.none {
            it in setOf("expenses.self.manage", "expenses.read", "expenses.manage")
        }
    )
        return expenseFailure("access_denied", FailureKind.AccessDenied)
    return AppResult.Success(
        boundExpenseAuthorization(
            ExpenseAccess(
                session.id,
                session.user.id,
                session.binding.audience,
                workspace.company.id,
                workspace.company.timezone,
                workspace.permissions.toSet(),
                Instant.fromEpochMilliseconds(session.binding.expiresAtEpochMillis),
                workspace.admission.expiresAt,
                "client.workspace_expired",
            ),
            requireNotNull(workspace.policy),
            now,
        )
    )
}

fun boundExpenseAuthorization(
    access: ExpenseAccess,
    policy: CompanyPolicySnapshot,
    now: Instant,
): ExpenseAccess {
    val start =
        policy.policy.maintenance?.let {
            policy.requestedAt + (it.startsAt - policy.policy.serverTime)
        }
    return if (start != null && start > now && start < access.authorizationExpiresAt)
        access.copy(authorizationExpiresAt = start, authorizationExpiryCode = "company_maintenance")
    else access
}

fun sameExpenseScope(first: ExpenseAccess, second: ExpenseAccess): Boolean =
    first.sessionId == second.sessionId &&
        first.accountId == second.accountId &&
        first.audience == second.audience &&
        first.companyId == second.companyId &&
        first.timezone == second.timezone &&
        first.permissions == second.permissions &&
        first.sessionExpiresAt == second.sessionExpiresAt

fun validateExpenseContinuation(
    expected: ExpenseAccess,
    current: AppResult<ExpenseAccess>,
): AppResult<ExpenseAccess> =
    when (current) {
        is AppResult.Failed -> current
        is AppResult.Success ->
            when {
                sameExpenseScope(expected, current.value) -> current
                sameStoredExpenseScope(expected, current.value) ->
                    expenseFailure("client.expense_changed", FailureKind.Conflict)
                else -> expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied)
            }
    }

fun validateExpenseActionSnapshot(
    access: ExpenseAccess,
    snapshot: ExpenseSnapshot<*>?,
    now: Instant,
): AppResult<Unit> =
    when {
        access.authorizationExpiresAt <= now ->
            expenseFailure(access.authorizationExpiryCode, FailureKind.Unavailable)
        snapshot == null -> AppResult.Success(Unit)
        !sameStoredExpenseScope(access, snapshot.access) ->
            expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied)
        !sameExpenseScope(access, snapshot.access) ->
            expenseFailure("client.expense_review_required", FailureKind.Unavailable)
        snapshot.fetchedAt > now ||
            snapshot.refreshAfter <= now ||
            snapshot.refreshAfter > snapshot.fetchedAt + EXPENSE_FRESHNESS_PERIOD ->
            expenseFailure("client.expense_review_required", FailureKind.Unavailable)
        else -> AppResult.Success(Unit)
    }

/** Stored facts remain readable after freshness expires; commands validate current authority. */
fun validateStoredExpense(access: ExpenseAccess, snapshot: ExpenseSnapshot<*>?): AppResult<Unit> =
    if (snapshot != null && !sameStoredExpenseScope(access, snapshot.access))
        expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied)
    else AppResult.Success(Unit)

fun sameStoredExpenseScope(first: ExpenseAccess, second: ExpenseAccess): Boolean =
    first.accountId == second.accountId &&
        first.audience == second.audience &&
        first.companyId == second.companyId
