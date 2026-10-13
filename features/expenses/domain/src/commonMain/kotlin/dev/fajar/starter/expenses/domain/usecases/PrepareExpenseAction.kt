@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.repositories.ExpenseCommandRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class PrepareExpenseAction(
    private val commands: ExpenseCommandRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        workspaceId: String,
        snapshot: ExpenseSnapshot<ExpenseView>,
        action: ExpenseRequestedAction,
        reason: String,
    ): AppResult<ExpenseCommandRecord> =
        withTimeoutOrNull(10.seconds) {
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val submissionId = Uuid.random().toString()
            val payload =
                when (
                    val result =
                        validateExpenseAction(
                            snapshot,
                            access,
                            action,
                            reason,
                            clock.now(),
                            submissionId,
                        )
                ) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val claim = snapshot.value.claim
            val slot = ExpenseCommandSlot.Claim(claim.id)
            val previous = commands.observe(access, slot).first()
            currentCoroutineContext().ensureActive()
            val ownership = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return@withTimeoutOrNull ownership
            val valid =
                validateExpenseAction(snapshot, access, action, reason, clock.now(), submissionId)
            if (valid is AppResult.Failed) return@withTimeoutOrNull valid
            val existing =
                when (previous) {
                    is AppResult.Failed -> return@withTimeoutOrNull previous
                    is AppResult.Success -> previous.value
                }
            val normalized = reason.trim()
            if (existing != null)
                return@withTimeoutOrNull retained(existing, access, claim, payload, normalized)
            val command =
                ExpenseCommand(
                    Uuid.random().toString(),
                    access.accountId,
                    access.audience,
                    access.companyId,
                    claim.id,
                    claim.employeeId,
                    claim.draft.employeeNumber,
                    null,
                    access.timezone,
                    payload,
                    normalized,
                    clock.now(),
                )
            val inserted = commands.insert(access, command)
            currentCoroutineContext().ensureActive()
            val continuation = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (continuation is AppResult.Failed) return@withTimeoutOrNull continuation
            when (inserted) {
                is AppResult.Failed -> return@withTimeoutOrNull inserted
                is AppResult.Success ->
                    when (val admission = admitExpenseCommandInsertion(inserted.value)) {
                        is AppResult.Failed -> return@withTimeoutOrNull admission
                        is AppResult.Success -> Unit
                    }
            }
            val stored = commands.observe(access, slot).first()
            currentCoroutineContext().ensureActive()
            val publication = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (publication is AppResult.Failed) return@withTimeoutOrNull publication
            when (stored) {
                is AppResult.Failed -> stored
                is AppResult.Success -> retained(stored.value, access, claim, payload, normalized)
            }
        } ?: expenseFailure("client.expense_timeout", FailureKind.Timeout)

    private fun retained(
        record: ExpenseCommandRecord?,
        access: ExpenseAccess,
        claim: ExpenseClaim,
        payload: ExpenseCommandPayload,
        reason: String,
    ): AppResult<ExpenseCommandRecord> {
        if (record != null && !record.command.belongsTo(access))
            return expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied)
        return if (
            record != null &&
                record.command.claimId == claim.id &&
                record.command.employeeId == claim.employeeId &&
                record.command.timezone == access.timezone &&
                sameExpenseIntent(payload, record.command.payload) &&
                record.command.reason == reason
        )
            AppResult.Success(record)
        else expenseFailure("client.expense_command_pending", FailureKind.Conflict)
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
}
