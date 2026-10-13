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

class PrepareExpenseDraft(
    private val commands: ExpenseCommandRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        workspaceId: String,
        prepared: ExpenseDraftPreparation,
        input: ExpenseDraftInput,
        expectedVersion: Long? = prepared.claim?.version,
    ): AppResult<ExpenseCommandRecord> =
        withTimeoutOrNull(10.seconds) {
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val valid = validateExpenseDraftPreparation(prepared, access, clock.now())
            if (valid is AppResult.Failed) return@withTimeoutOrNull valid
            if (prepared.claim?.version != expectedVersion)
                return@withTimeoutOrNull expenseFailure(
                    "client.expense_changed",
                    FailureKind.Conflict,
                )
            val explanation = validateExpenseReason(input.reason)
            if (explanation is AppResult.Failed) return@withTimeoutOrNull explanation
            val payload =
                when (val result = normalizeExpenseDraft(input, expectedVersion)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val slot =
                prepared.claim?.let { ExpenseCommandSlot.Claim(it.id) }
                    ?: ExpenseCommandSlot.Creation(prepared.employee.id)
            val previous = commands.observe(access, slot).first()
            currentCoroutineContext().ensureActive()
            val ownership = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return@withTimeoutOrNull ownership
            val stillValid = validateExpenseDraftPreparation(prepared, access, clock.now())
            if (stillValid is AppResult.Failed) return@withTimeoutOrNull stillValid
            val existing =
                when (previous) {
                    is AppResult.Failed -> return@withTimeoutOrNull previous
                    is AppResult.Success -> previous.value
                }
            val normalized = input.reason.trim()
            if (existing != null)
                return@withTimeoutOrNull retained(existing, access, prepared, payload, normalized)
            val command =
                ExpenseCommand(
                    Uuid.random().toString(),
                    access.accountId,
                    access.audience,
                    access.companyId,
                    prepared.claim?.id ?: Uuid.random().toString(),
                    prepared.employee.id,
                    prepared.employee.employeeNumber,
                    prepared.employmentAfter,
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
                is AppResult.Success ->
                    retained(stored.value, access, prepared, payload, normalized)
            }
        } ?: expenseFailure("client.expense_timeout", FailureKind.Timeout)

    private fun retained(
        record: ExpenseCommandRecord?,
        access: ExpenseAccess,
        prepared: ExpenseDraftPreparation,
        payload: ExpenseCommandPayload.SaveDraft,
        reason: String,
    ): AppResult<ExpenseCommandRecord> {
        if (record != null && !record.command.belongsTo(access))
            return expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied)
        return if (
            record != null &&
                record.command.employeeId == prepared.employee.id &&
                record.command.timezone == access.timezone &&
                record.command.payload == payload &&
                (prepared.claim == null || record.command.claimId == prepared.claim.id) &&
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
