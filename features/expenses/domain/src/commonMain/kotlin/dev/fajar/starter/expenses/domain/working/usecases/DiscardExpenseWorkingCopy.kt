package dev.fajar.starter.expenses.domain.working.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.repositories.ExpenseReplicaRepository
import dev.fajar.starter.expenses.domain.working.entities.*
import dev.fajar.starter.expenses.domain.working.policies.*
import dev.fajar.starter.expenses.domain.working.repositories.ExpenseWorkingCopyRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

class DiscardExpenseWorkingCopy(
    private val copies: ExpenseWorkingCopyRepository,
    private val replicas: ExpenseReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        workspaceId: String,
        slot: ExpenseWorkingSlot,
        expectedRevision: String,
    ): AppResult<Boolean> =
        withTimeoutOrNull(10.seconds) {
            val valid = validateExpenseWorkingSlot(slot)
            if (valid is AppResult.Failed) return@withTimeoutOrNull valid
            if (validateExpenseId(expectedRevision) is AppResult.Failed)
                return@withTimeoutOrNull expenseFailure("client.expense_working_copy_invalid")
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val removed = copies.remove(access, slot, expectedRevision)
            currentCoroutineContext().ensureActive()
            val owner = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (owner is AppResult.Failed) return@withTimeoutOrNull owner
            when (removed) {
                is AppResult.Failed -> removed
                is AppResult.Success ->
                    if (removed.value) removed
                    else expenseFailure("client.expense_working_copy_changed", FailureKind.Conflict)
            }
        } ?: expenseFailure("client.expense_timeout", FailureKind.Timeout)

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
        val access = admitExpenses(workspaceId, workspace, latest, build, clock.now())
        if (access is AppResult.Failed) return access
        val value = (access as AppResult.Success).value
        val editing = admitExpenseEditing(value)
        if (editing is AppResult.Failed) return editing
        val admission = replicas.admission(value)
        currentCoroutineContext().ensureActive()
        if (admission is AppResult.Failed) return admission
        (admission as AppResult.Success).value.denial?.let {
            return AppResult.Failed(it)
        }
        val checkedSession =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return expenseFailure("session_revoked", FailureKind.Unauthorized)
            }
        val checkedWorkspace =
            when (val result = workspaces.current(checkedSession.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val finalSession =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return validateExpenseContinuation(
            value,
            admitExpenses(workspaceId, checkedWorkspace, finalSession, build, clock.now()),
        )
    }
}
