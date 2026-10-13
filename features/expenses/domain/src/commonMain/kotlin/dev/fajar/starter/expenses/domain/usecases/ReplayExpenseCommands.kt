@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.repositories.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes manual/background delivery; every attempt reuses the retained operation and body. */
class ReplayExpenseCommands(
    private val commands: ExpenseCommandRepository,
    private val remote: ExpenseSubmissionRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) : SyncTask {
    override val key = KEY
    private val execution = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        commandId: String,
    ): AppResult<ExpenseCommandRecord> =
        withTimeoutOrNull(30.seconds) {
            if (Uuid.parseOrNull(commandId)?.toString() != commandId)
                return@withTimeoutOrNull expenseFailure(
                    "client.expense_command_changed",
                    FailureKind.Conflict,
                )
            execution.withLock {
                val access =
                    when (val result = currentAccess(workspaceId)) {
                        is AppResult.Failed -> return@withLock result
                        is AppResult.Success -> result.value
                    }
                val stored = commands.find(access, commandId)
                currentCoroutineContext().ensureActive()
                val ownership = validateExpenseContinuation(access, currentAccess(workspaceId))
                if (ownership is AppResult.Failed) return@withLock ownership
                val record =
                    when (stored) {
                        is AppResult.Failed -> return@withLock stored
                        is AppResult.Success ->
                            stored.value
                                ?: return@withLock expenseFailure(
                                    "client.expense_command_changed",
                                    FailureKind.Conflict,
                                )
                    }
                currentCoroutineContext().ensureActive()
                deliver(workspaceId, access, record)
            }
        } ?: expenseFailure("client.expense_timeout", FailureKind.Timeout)

    override suspend fun invoke(): SyncResult =
        withTimeoutOrNull(30.seconds) { execution.withLock { deliverBatch() } }
            ?: SyncResult.Retry(
                Failure(
                    FailureKind.Timeout,
                    "Expense delivery timed out.",
                    code = "client.expense_timeout",
                )
            )

    private suspend fun deliverBatch(): SyncResult {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value ?: return SyncResult.Complete
            }
        currentCoroutineContext().ensureActive()
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value ?: return SyncResult.Complete
            }
        val access =
            when (val result = currentAccess(workspace.id)) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value
            }
        var cursor: String? = null
        var delivered = 0
        // The account command store admits at most 128 records; three 50-row pages cover it.
        repeat(3) {
            val records = commands.page(access, cursor)
            currentCoroutineContext().ensureActive()
            val ownership = validateExpenseContinuation(access, currentAccess(workspace.id))
            if (ownership is AppResult.Failed) return syncFailure(ownership.failure)
            val page =
                when (records) {
                    is AppResult.Failed -> return syncFailure(records.failure)
                    is AppResult.Success -> records.value
                }
            if (page.items.size > 50)
                return SyncResult.Blocked(
                    Failure(
                        FailureKind.Storage,
                        "Expense delivery progress is unavailable.",
                        code = "client.invalid_command_page",
                    )
                )
            for (record in page.items) {
                if (
                    record.attempts >= EXPENSE_AUTOMATIC_ATTEMPTS ||
                        record.outcome is ExpenseCommandOutcome.Confirmed ||
                        record.outcome is ExpenseCommandOutcome.Rejected
                )
                    continue
                when (val result = deliver(workspace.id, access, record)) {
                    is AppResult.Failed -> return syncFailure(result.failure)
                    is AppResult.Success ->
                        when (val outcome = result.value.outcome) {
                            is ExpenseCommandOutcome.Unconfirmed ->
                                return outcome.failure?.let(::syncFailure) ?: SyncResult.Retry()
                            is ExpenseCommandOutcome.Rejected ->
                                return SyncResult.Blocked(outcome.failure)
                            else -> Unit
                        }
                }
                delivered++
                if (delivered == 10) return SyncResult.Retry()
            }
            val next = page.nextCursor ?: return SyncResult.Complete
            if (cursor != null && next <= cursor)
                return SyncResult.Blocked(
                    Failure(
                        FailureKind.Storage,
                        "Expense delivery progress is unavailable.",
                        code = "client.invalid_command_page",
                    )
                )
            cursor = next
        }
        return SyncResult.Retry()
    }

    private suspend fun deliver(
        workspaceId: String,
        access: ExpenseAccess,
        record: ExpenseCommandRecord,
    ): AppResult<ExpenseCommandRecord> {
        if (!record.command.belongsTo(access))
            return expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied)
        val ownership = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        val permission = admitExpenseEditing(access)
        if (permission is AppResult.Failed) return permission
        if (
            record.outcome is ExpenseCommandOutcome.Confirmed ||
                record.outcome is ExpenseCommandOutcome.Rejected
        )
            return AppResult.Success(record)
        if (record.attempts >= EXPENSE_MAXIMUM_ATTEMPTS)
            return expenseFailure("client.expense_retry_limit", FailureKind.Conflict)
        val policy =
            when (val result = currentPolicy(workspaceId, access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val attempt = record.attempts + 1
        when (
            val result =
                commands.update(access, record, ExpenseCommandOutcome.Unconfirmed(), attempt)
        ) {
            is AppResult.Failed -> return result
            is AppResult.Success ->
                if (!result.value)
                    return expenseFailure("client.expense_command_changed", FailureKind.Conflict)
        }
        val sending =
            record.copy(
                revision = record.revision + 1,
                attempts = attempt,
                outcome = ExpenseCommandOutcome.Unconfirmed(),
            )
        currentCoroutineContext().ensureActive()
        val continuation = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (continuation is AppResult.Failed) return continuation
        val available = admitCompanyOperation(policy, build, CompanyModule.EXPENSES, clock.now())
        if (available is AppResult.Failed) return available
        val response = remote.send(access.sessionId, record.command)
        currentCoroutineContext().ensureActive()
        val outcome =
            when (response) {
                is AppResult.Failed ->
                    resolveExpenseCommandFailure(record.outcome, response.failure)
                is AppResult.Success ->
                    if (
                        response.value.id == record.command.claimId &&
                            response.value.version == record.command.expectedReceiptVersion()
                    )
                        ExpenseCommandOutcome.Confirmed(response.value.version)
                    else
                        ExpenseCommandOutcome.Unconfirmed(
                            Failure(
                                FailureKind.Unexpected,
                                "The expense response could not be read.",
                                code = "client.invalid_response",
                            )
                        )
            }
        // Retain evidence in its original account even when the initiating screen was replaced.
        when (val result = commands.update(access, sending, outcome)) {
            is AppResult.Failed -> return result
            is AppResult.Success ->
                if (!result.value)
                    return expenseFailure("client.expense_command_changed", FailureKind.Conflict)
        }
        currentCoroutineContext().ensureActive()
        val publication = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (publication is AppResult.Failed) return publication
        return AppResult.Success(sending.copy(revision = sending.revision + 1, outcome = outcome))
    }

    private suspend fun currentPolicy(
        workspaceId: String,
        access: ExpenseAccess,
    ): AppResult<CompanyPolicySnapshot> {
        val requestedAt = clock.now()
        val response = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        val ownership = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        val snapshot =
            when (response) {
                is AppResult.Failed -> return response
                is AppResult.Success -> CompanyPolicySnapshot(response.value, requestedAt)
            }
        return when (
            val available =
                admitCompanyOperation(snapshot, build, CompanyModule.EXPENSES, clock.now())
        ) {
            is AppResult.Failed -> available
            is AppResult.Success -> AppResult.Success(snapshot)
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

    companion object {
        const val KEY = "hris-expense-commands"
    }
}
