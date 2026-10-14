package dev.fajar.starter.expenses.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.repositories.ExpenseReplicaRepository
import dev.fajar.starter.expenses.domain.repositories.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.datetime.LocalDate

class LoadExpenseCategories(
    private val remote: ExpensePreparationRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val replicas: ExpenseReplicaRepository,
) {
    suspend operator fun invoke(
        workspaceId: String,
        asOf: LocalDate,
        after: String? = null,
    ): AppResult<ExpenseCategories> {
        val valid = validateExpenseCategoryQuery(asOf, after)
        if (valid is AppResult.Failed) return valid
        return withTimeoutOrNull(30.seconds) {
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val requestedAt = clock.now()
            val fetchedPolicy = workspaces.clientPolicy(access.sessionId, access.companyId)
            currentCoroutineContext().ensureActive()
            val ownership = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return@withTimeoutOrNull ownership
            val policy =
                when (fetchedPolicy) {
                    is AppResult.Failed ->
                        return@withTimeoutOrNull retainReadDenial(
                            workspaceId,
                            access,
                            fetchedPolicy,
                        )
                    is AppResult.Success -> CompanyPolicySnapshot(fetchedPolicy.value, requestedAt)
                }
            val admission = continuation(workspaceId, access, policy)
            if (admission is AppResult.Failed) return@withTimeoutOrNull admission
            val response = remote.categories(access, asOf, after)
            val publication =
                when (val result = continuation(workspaceId, access, policy)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val page =
                when (response) {
                    is AppResult.Failed ->
                        return@withTimeoutOrNull retainReadDenial(workspaceId, access, response)
                    is AppResult.Success -> response.value
                }
            val expiry =
                when (
                    val result =
                        expensePreparationExpiry(publication, policy, requestedAt, clock.now())
                ) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            AppResult.Success(
                ExpenseCategories(publication, asOf, after, page, requestedAt, expiry)
            )
        } ?: expenseFailure("client.expense_timeout", FailureKind.Timeout)
    }

    private suspend fun retainReadDenial(
        workspaceId: String,
        access: ExpenseAccess,
        result: AppResult.Failed,
    ): AppResult.Failed {
        if (expenseFailureInvalidatesCache(result.failure)) {
            val before = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (before is AppResult.Failed) return before
            replicas.restrict(access, result.failure)
            currentCoroutineContext().ensureActive()
            val after = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (after is AppResult.Failed) return after
        }
        return result
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<ExpenseAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return expenseFailure("session_revoked", FailureKind.Unauthorized)
            }
        currentCoroutineContext().ensureActive()
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val latest =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val access = admitExpenses(workspaceId, workspace, latest, build, clock.now())
        if (access is AppResult.Failed) return access
        val editing = admitExpenseEditing((access as AppResult.Success).value)
        return if (editing is AppResult.Failed) editing else access
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
}
