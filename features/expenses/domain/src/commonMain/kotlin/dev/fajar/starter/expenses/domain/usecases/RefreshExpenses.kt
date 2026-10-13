@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.repositories.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.datetime.*

class RefreshExpenses(
    private val remote: ExpenseRepository,
    private val cache: ExpenseCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(workspaceId: String, selection: ExpenseSelection): AppResult<Unit> {
        val valid = validateExpenseSelection(selection)
        if (valid is AppResult.Failed) return valid
        val access =
            when (val result = withTimeoutOrNull(5.seconds) { currentAccess(workspaceId) }) {
                null -> return expenseFailure("client.expense_timeout", FailureKind.Timeout)
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val generation =
            when (val result = withTimeoutOrNull(5.seconds) { cache.generation(access) }) {
                null -> return expenseFailure("client.expense_timeout", FailureKind.Timeout)
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val result =
            withTimeoutOrNull(30.seconds) { acquire(workspaceId, selection, access, generation) }
                ?: expenseFailure("client.expense_timeout", FailureKind.Timeout)
        currentCoroutineContext().ensureActive()
        if (result is AppResult.Failed && expenseFailureInvalidatesCache(result.failure)) {
            // Cleanup has its own budget. A blocked deletion cannot turn a known denial into a
            // timeout.
            withTimeoutOrNull(2.seconds) { cache.replace(access, generation, null) }
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    private suspend fun acquire(
        workspaceId: String,
        selection: ExpenseSelection,
        access: ExpenseAccess,
        generation: String?,
    ): AppResult<Unit> {
        val requestedAt = clock.now()
        val policy =
            when (val result = workspaces.clientPolicy(access.sessionId, access.companyId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> CompanyPolicySnapshot(result.value, requestedAt)
            }
        val admitted = continuation(workspaceId, access, policy)
        if (admitted is AppResult.Failed) return admitted
        val directory =
            when (val result = remote.directory(access, selection.employmentAfter)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val directoryAccess = continuation(workspaceId, access, policy)
        if (directoryAccess is AppResult.Failed) return directoryAccess
        if (directory.accountId != access.accountId || directory.companyId != access.companyId)
            return expenseFailure("client.invalid_response", FailureKind.Unexpected)
        if (directory.timezone != access.timezone)
            return expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied)
        val employee =
            if (selection.employeeId == null)
                directory.items.firstOrNull { it.status == ExpenseEmploymentStatus.ACTIVE }
                    ?: directory.items.firstOrNull {
                        it.status == ExpenseEmploymentStatus.PROBATION
                    }
                    ?: directory.items.firstOrNull()
            else directory.items.firstOrNull { it.id == selection.employeeId }
        if (selection.employeeId != null && employee == null)
            return expenseFailure("employee_not_found", FailureKind.AccessDenied)
        val selected =
            selection.copy(
                employeeId = employee?.id,
                month = selection.month ?: YearMonth(directory.asOf.year, directory.asOf.month),
            )
        val valid = validateExpenseSelection(selected)
        if (valid is AppResult.Failed) return valid
        val page =
            if (employee == null) ExpensePage<ExpenseSummary>(emptyList())
            else
                when (val result = remote.list(access, selected)) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
        val current =
            when (val result = continuation(workspaceId, access, policy)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (
            page.items.any {
                val created = it.createdAt.toLocalDateTime(TimeZone.of(directory.timezone))
                it.employeeId != selected.employeeId ||
                    YearMonth(created.year, created.month) != selected.month ||
                    (selected.status != null && it.status != selected.status)
            }
        )
            return expenseFailure("client.invalid_response", FailureKind.Unexpected)
        val now = clock.now()
        val lease = boundExpenseAuthorization(current, policy, now)
        val snapshot =
            ExpenseSnapshot(
                lease,
                Uuid.random().toString(),
                ExpenseOverview(directory, selected, page),
                now,
                now + EXPENSE_FRESHNESS_PERIOD,
            )
        when (val result = cache.replace(access, generation, snapshot)) {
            is AppResult.Failed -> return result
            is AppResult.Success ->
                if (!result.value)
                    return expenseFailure("client.expense_changed", FailureKind.Conflict)
        }
        val latest = continuation(workspaceId, access, policy)
        return if (latest is AppResult.Failed) latest else AppResult.Success(Unit)
    }

    private suspend fun continuation(
        workspaceId: String,
        expected: ExpenseAccess,
        policy: CompanyPolicySnapshot,
    ): AppResult<ExpenseAccess> {
        currentCoroutineContext().ensureActive()
        val access = validateExpenseContinuation(expected, currentAccess(workspaceId))
        if (access is AppResult.Failed) return access
        val admitted = admitCompanyOperation(policy, build, CompanyModule.EXPENSES, clock.now())
        return if (admitted is AppResult.Failed) admitted else access
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<ExpenseAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return expenseFailure("session_revoked", FailureKind.Unauthorized)
            }
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val latest =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return admitExpenses(workspaceId, workspace, latest, build, clock.now())
    }
}
