@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.repositories.ExpenseReplicaRepository
import dev.fajar.starter.expenses.domain.repositories.*
import dev.fajar.starter.expenses.domain.working.entities.*
import dev.fajar.starter.expenses.domain.working.repositories.ExpenseWorkingCopyRepository
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
    private val cache: ExpenseReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val copies: ExpenseWorkingCopyRepository,
) {
    suspend operator fun invoke(
        workspaceId: String,
        commandId: String,
        expectedRevision: Long? = null,
    ): AppResult<Boolean> =
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
            if (expectedRevision != null && record.revision != expectedRevision)
                return@withTimeoutOrNull expenseFailure(
                    "client.expense_command_changed",
                    FailureKind.Conflict,
                )
            if (record.outcome is ExpenseCommandOutcome.Unconfirmed)
                return@withTimeoutOrNull expenseFailure(
                    "client.expense_unconfirmed",
                    FailureKind.Conflict,
                )
            if (record.outcome is ExpenseCommandOutcome.Confirmed) {
                val admitted = readAdmission(workspaceId, access)
                if (admitted is AppResult.Failed) return@withTimeoutOrNull admitted
                val observed = cache.observeDetails(access, record.command.claimId).first()
                currentCoroutineContext().ensureActive()
                val continuation = validateExpenseContinuation(access, currentAccess(workspaceId))
                if (continuation is AppResult.Failed) return@withTimeoutOrNull continuation
                val snapshot =
                    when (observed) {
                        is AppResult.Failed -> return@withTimeoutOrNull observed
                        is AppResult.Success -> observed.value.snapshot
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
                val publication = readAdmission(workspaceId, access)
                if (publication is AppResult.Failed) return@withTimeoutOrNull publication
                val cleaned = removeSavedInput(workspaceId, access, record.command)
                if (cleaned is AppResult.Failed) return@withTimeoutOrNull cleaned
            }
            val removal = commands.remove(access, record)
            currentCoroutineContext().ensureActive()
            val publication = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (publication is AppResult.Failed) publication else removal
        } ?: expenseFailure("client.expense_timeout", FailureKind.Timeout)

    /** Confirmed delivery may remove only the exact input it acknowledged, never newer edits. */
    private suspend fun removeSavedInput(
        workspaceId: String,
        access: ExpenseAccess,
        command: ExpenseCommand,
    ): AppResult<Unit> {
        val payload =
            command.payload as? ExpenseCommandPayload.SaveDraft ?: return AppResult.Success(Unit)
        for (slot in
            listOf(
                ExpenseWorkingSlot.Claim(command.claimId),
                ExpenseWorkingSlot.Creation(command.employeeId),
            )) {
            val observed = copies.observe(access, slot).first()
            currentCoroutineContext().ensureActive()
            val continuation = readAdmission(workspaceId, access)
            if (continuation is AppResult.Failed) return continuation
            val copy =
                when (observed) {
                    is AppResult.Failed -> return observed
                    is AppResult.Success -> observed.value ?: continue
                }
            if (
                copy.claimId != command.claimId ||
                    copy.employee.id != command.employeeId ||
                    copy.item != null ||
                    copy.expectedVersion != payload.expectedVersion ||
                    copy.input.reason.trim() != command.reason
            )
                continue
            val normalized = normalizeExpenseDraft(copy.input, copy.expectedVersion)
            if (normalized !is AppResult.Success || normalized.value != payload) continue
            val removed = copies.remove(access, slot, copy.revision)
            currentCoroutineContext().ensureActive()
            val publication = readAdmission(workspaceId, access)
            if (publication is AppResult.Failed) return publication
            if (removed is AppResult.Failed) return removed
            // A compare-and-set miss means a newer working copy remains; the command is still
            // confirmed.
        }
        return AppResult.Success(Unit)
    }

    private suspend fun readAdmission(workspaceId: String, access: ExpenseAccess): AppResult<Unit> {
        val admission = cache.admission(access)
        currentCoroutineContext().ensureActive()
        val owner = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (owner is AppResult.Failed) return owner
        return when (admission) {
            is AppResult.Failed -> admission
            is AppResult.Success ->
                admission.value.denial?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
        }
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
