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

class RefreshExpenseClaim(
    private val remote: ExpenseRepository,
    private val cache: ExpenseCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        workspaceId: String,
        id: String,
        historyAfter: Long? = null,
    ): AppResult<Unit> {
        val valid = validateExpenseId(id)
        if (valid is AppResult.Failed) return valid
        val cursor = validateExpenseHistoryCursor(historyAfter)
        if (cursor is AppResult.Failed) return cursor
        val access =
            when (val result = withTimeoutOrNull(5.seconds) { currentAccess(workspaceId) }) {
                null -> return expenseFailure("client.expense_timeout", FailureKind.Timeout)
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val generation =
            when (val result = withTimeoutOrNull(5.seconds) { cache.detailsGeneration(access) }) {
                null -> return expenseFailure("client.expense_timeout", FailureKind.Timeout)
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val result =
            withTimeoutOrNull(30.seconds) {
                acquire(workspaceId, id, historyAfter, access, generation)
            } ?: expenseFailure("client.expense_timeout", FailureKind.Timeout)
        currentCoroutineContext().ensureActive()
        if (result is AppResult.Failed && expenseFailureInvalidatesCache(result.failure)) {
            withTimeoutOrNull(2.seconds) { cache.replaceDetails(access, generation, null) }
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    private suspend fun acquire(
        workspaceId: String,
        id: String,
        historyAfter: Long?,
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
        val claim =
            when (val result = remote.claim(access, id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val claimAccess = continuation(workspaceId, access, policy)
        if (claimAccess is AppResult.Failed) return claimAccess
        if (claim.id != id) return expenseFailure("client.invalid_response", FailureKind.Unexpected)
        val history =
            when (val result = remote.history(access, id, historyAfter)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val historyAccess = continuation(workspaceId, access, policy)
        if (historyAccess is AppResult.Failed) return historyAccess
        val submission =
            claim.latestSubmissionId?.let { submissionId ->
                when (val result = remote.submission(access, submissionId)) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
            }
        val submissionAccess = continuation(workspaceId, access, policy)
        if (submissionAccess is AppResult.Failed) return submissionAccess
        val payments =
            when (val result = remote.payments(access, id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val paymentAccess = continuation(workspaceId, access, policy)
        if (paymentAccess is AppResult.Failed) return paymentAccess
        // Mutable claim reads must belong to the same observed revision.
        val confirmed =
            when (val result = remote.claim(access, id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val current =
            when (val result = continuation(workspaceId, access, policy)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (confirmed != claim)
            return expenseFailure("client.expense_changed", FailureKind.Conflict)
        val view = ExpenseView(claim, submission, history, historyAfter, payments)
        val valid = validateExpenseView(view)
        if (valid is AppResult.Failed) return valid
        val now = clock.now()
        val lease = boundExpenseAuthorization(current, policy, now)
        val snapshot =
            ExpenseSnapshot(
                lease,
                Uuid.random().toString(),
                view,
                now,
                now + EXPENSE_FRESHNESS_PERIOD,
            )
        when (val result = cache.replaceDetails(access, generation, snapshot)) {
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
