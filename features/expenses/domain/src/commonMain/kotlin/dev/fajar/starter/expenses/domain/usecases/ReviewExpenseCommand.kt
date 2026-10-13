@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.repositories.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Discards unsent/rejected input; a confirmed command first needs admitted updated request facts.
 */
class ReviewExpenseCommand(
    private val commands: ExpenseCommandRepository,
    private val cache: ExpenseCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(workspaceId: String, commandId: String): AppResult<Boolean> =
        withTimeoutOrNull(10.seconds) {
            if (Uuid.parseOrNull(commandId)?.toString() != commandId)
                return@withTimeoutOrNull expenseFailure(
                    "client.expense_command_changed",
                    FailureKind.Conflict,
                )
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val stored = commands.find(access, commandId)
            currentCoroutineContext().ensureActive()
            val ownership = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return@withTimeoutOrNull ownership
            val record =
                when (stored) {
                    is AppResult.Failed -> return@withTimeoutOrNull stored
                    is AppResult.Success ->
                        stored.value ?: return@withTimeoutOrNull AppResult.Success(false)
                }
            if (!record.command.belongsTo(access))
                return@withTimeoutOrNull expenseFailure(
                    "client.expense_scope_changed",
                    FailureKind.AccessDenied,
                )
            if (record.outcome is ExpenseCommandOutcome.Unconfirmed)
                return@withTimeoutOrNull expenseFailure(
                    "client.expense_unconfirmed",
                    FailureKind.Conflict,
                )
            if (record.outcome is ExpenseCommandOutcome.Confirmed) {
                val observed = cache.observeDetails(access).first()
                currentCoroutineContext().ensureActive()
                val continuation = validateExpenseContinuation(access, currentAccess(workspaceId))
                if (continuation is AppResult.Failed) return@withTimeoutOrNull continuation
                val snapshot =
                    when (observed) {
                        is AppResult.Failed -> return@withTimeoutOrNull observed
                        is AppResult.Success -> observed.value
                    }
                val lease = validateExpenseActionSnapshot(access, snapshot, clock.now())
                if (lease is AppResult.Failed) return@withTimeoutOrNull lease
                if (snapshot == null)
                    return@withTimeoutOrNull expenseFailure(
                        "client.expense_updates_pending",
                        FailureKind.Conflict,
                    )
                val request = snapshot.value.claim
                if (
                    request.id != record.command.claimId ||
                        request.employeeId != record.command.employeeId ||
                        request.version < record.outcome.version
                )
                    return@withTimeoutOrNull expenseFailure(
                        "client.expense_updates_pending",
                        FailureKind.Conflict,
                    )
            }
            val removal = commands.remove(access, record)
            currentCoroutineContext().ensureActive()
            val publication = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (publication is AppResult.Failed) publication else removal
        } ?: expenseFailure("client.expense_timeout", FailureKind.Timeout)

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
}
