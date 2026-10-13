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
import kotlinx.coroutines.*

class LoadExpenseDraftPreparation(
    private val remote: ExpenseRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        workspaceId: String,
        employeeId: String,
        claimId: String? = null,
        employmentAfter: String? = null,
    ): AppResult<ExpenseDraftPreparation> {
        val valid =
            validateExpenseSelection(
                ExpenseSelection(employeeId = employeeId, employmentAfter = employmentAfter)
            )
        if (valid is AppResult.Failed) return valid
        if (claimId != null && validateExpenseId(claimId) is AppResult.Failed)
            return expenseFailure("expense_claim_not_found")
        return withTimeoutOrNull(30.seconds) {
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            acquire(workspaceId, employeeId, claimId, employmentAfter, access)
        } ?: expenseFailure("client.expense_timeout", FailureKind.Timeout)
    }

    private suspend fun acquire(
        workspaceId: String,
        employeeId: String,
        claimId: String?,
        employmentAfter: String?,
        access: ExpenseAccess,
    ): AppResult<ExpenseDraftPreparation> {
        val requestedAt = clock.now()
        val fetchedPolicy = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        val ownership = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        val policy =
            when (fetchedPolicy) {
                is AppResult.Failed -> return fetchedPolicy
                is AppResult.Success -> CompanyPolicySnapshot(fetchedPolicy.value, requestedAt)
            }
        val admission = continuation(workspaceId, access, policy)
        if (admission is AppResult.Failed) return admission
        val response = remote.directory(access, employmentAfter)
        val directoryAccess = continuation(workspaceId, access, policy)
        if (directoryAccess is AppResult.Failed) return directoryAccess
        val directory =
            when (response) {
                is AppResult.Failed -> return response
                is AppResult.Success -> response.value
            }
        if (directory.accountId != access.accountId || directory.companyId != access.companyId)
            return expenseFailure("client.invalid_response", FailureKind.Unexpected)
        if (directory.timezone != access.timezone)
            return expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied)
        val employee =
            directory.items.firstOrNull { it.id == employeeId }
                ?: return expenseFailure("employee_not_found", FailureKind.AccessDenied)
        val claim =
            when (
                val result = loadEditableClaim(workspaceId, access, policy, claimId, employee.id)
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val publication =
            when (val result = continuation(workspaceId, access, policy)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val expiry =
            when (
                val result = expensePreparationExpiry(publication, policy, requestedAt, clock.now())
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        return AppResult.Success(
            ExpenseDraftPreparation(
                publication,
                employee,
                directory.asOf,
                claim,
                requestedAt,
                expiry,
                employmentAfter,
            )
        )
    }

    private suspend fun loadEditableClaim(
        workspaceId: String,
        access: ExpenseAccess,
        policy: CompanyPolicySnapshot,
        claimId: String?,
        employeeId: String,
    ): AppResult<ExpenseClaim?> {
        if (claimId == null) return AppResult.Success(null)
        val response = remote.claim(access, claimId)
        val claimed = continuation(workspaceId, access, policy)
        if (claimed is AppResult.Failed) return claimed
        val claim =
            when (response) {
                is AppResult.Failed -> return response
                is AppResult.Success -> response.value
            }
        if (claim.id != claimId || claim.employeeId != employeeId)
            return expenseFailure("client.invalid_response", FailureKind.Unexpected)
        if (claim.status !in setOf(ExpenseStatus.DRAFT, ExpenseStatus.RETURNED))
            return expenseFailure("expense_not_editable", FailureKind.Conflict)
        val amounts = validateExpenseDraftAmounts(claim.draft)
        return if (amounts is AppResult.Failed) amounts else AppResult.Success(claim)
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
