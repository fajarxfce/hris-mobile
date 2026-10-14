@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.domain.working.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.policies.validateStoredExpenseReplica
import dev.fajar.starter.expenses.domain.replica.repositories.ExpenseReplicaRepository
import dev.fajar.starter.expenses.domain.working.entities.*
import dev.fajar.starter.expenses.domain.working.policies.*
import dev.fajar.starter.expenses.domain.working.repositories.ExpenseWorkingCopyRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Opens owned input locally. A fresh preparation is only a fallback when no local facts exist. */
class OpenExpenseWorkingCopy(
    private val copies: ExpenseWorkingCopyRepository,
    private val replicas: ExpenseReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        workspaceId: String,
        employeeId: String,
        slot: ExpenseWorkingSlot,
        prepared: ExpenseDraftPreparation? = null,
        recoveredCommand: ExpenseCommand? = null,
    ): AppResult<ExpenseWorkingCopy> =
        withTimeoutOrNull(10.seconds) {
            val valid = validateExpenseWorkingSlot(slot)
            if (valid is AppResult.Failed) return@withTimeoutOrNull valid
            if (
                validateExpenseId(employeeId) is AppResult.Failed ||
                    slot is ExpenseWorkingSlot.Creation && slot.employeeId != employeeId
            )
                return@withTimeoutOrNull expenseFailure("client.expense_working_copy_invalid")
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val recovered = recoveredCommand?.payload as? ExpenseCommandPayload.SaveDraft
            if (recoveredCommand != null) {
                val commandSlot =
                    when (slot) {
                        is ExpenseWorkingSlot.Creation ->
                            ExpenseCommandSlot.Creation(slot.employeeId)
                        is ExpenseWorkingSlot.Claim -> ExpenseCommandSlot.Claim(slot.claimId)
                    }
                if (
                    recovered == null ||
                        !recoveredCommand.belongsTo(access) ||
                        recoveredCommand.employeeId != employeeId ||
                        recoveredCommand.slot() != commandSlot
                )
                    return@withTimeoutOrNull expenseFailure(
                        "client.expense_scope_changed",
                        FailureKind.AccessDenied,
                    )
            }
            val observed = copies.observe(access, slot).first()
            currentCoroutineContext().ensureActive()
            val ownership = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return@withTimeoutOrNull ownership
            val existing =
                when (observed) {
                    is AppResult.Failed -> return@withTimeoutOrNull observed
                    is AppResult.Success -> observed.value
                }
            if (existing != null) {
                val shape = validateExpenseWorkingCopy(slot, existing)
                if (shape is AppResult.Failed) return@withTimeoutOrNull shape
                if (existing.employee.id != employeeId)
                    return@withTimeoutOrNull expenseFailure(
                        "client.expense_scope_changed",
                        FailureKind.AccessDenied,
                    )
                if (recoveredCommand != null && existing.claimId != recoveredCommand.claimId)
                    return@withTimeoutOrNull expenseFailure(
                        "client.expense_working_copy_changed",
                        FailureKind.Conflict,
                    )
                return@withTimeoutOrNull AppResult.Success(existing)
            }
            val copy =
                when (
                    val result =
                        loadInitialCopy(
                            workspaceId,
                            access,
                            employeeId,
                            slot,
                            prepared,
                            recoveredCommand,
                        )
                ) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val insertion = copies.save(access, slot, null, copy)
            currentCoroutineContext().ensureActive()
            val publication = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (publication is AppResult.Failed) return@withTimeoutOrNull publication
            when (insertion) {
                is AppResult.Failed -> insertion
                is AppResult.Success ->
                    insertion.value?.let { AppResult.Success(it) }
                        ?: expenseFailure(
                            "client.expense_working_copy_changed",
                            FailureKind.Conflict,
                        )
            }
        } ?: expenseFailure("client.expense_timeout", FailureKind.Timeout)

    private suspend fun loadInitialCopy(
        workspaceId: String,
        access: ExpenseAccess,
        employeeId: String,
        slot: ExpenseWorkingSlot,
        prepared: ExpenseDraftPreparation?,
        recoveredCommand: ExpenseCommand?,
    ): AppResult<ExpenseWorkingCopy> {
        val recovered = recoveredCommand?.payload as? ExpenseCommandPayload.SaveDraft
        val directoryResult = replicas.observeDirectory(access).first()
        currentCoroutineContext().ensureActive()
        val directoryOwner = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (directoryOwner is AppResult.Failed) return directoryOwner
        val local =
            when (directoryResult) {
                is AppResult.Failed -> return directoryResult
                is AppResult.Success -> directoryResult.value
            }
        val owner = validateStoredExpenseReplica(access, local.checkpoint)
        if (owner is AppResult.Failed) return owner
        val directory = local.directory
        if (
            directory != null &&
                (directory.accountId != access.accountId ||
                    directory.companyId != access.companyId ||
                    directory.items.none { it.id == employeeId })
        )
            return expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied)
        val employee: ExpenseEmployment
        val claim: ExpenseClaim?
        if (prepared != null) {
            val fresh = validateExpenseDraftPreparation(prepared, access, clock.now())
            if (fresh is AppResult.Failed) return fresh
            if (
                prepared.employee.id != employeeId ||
                    (slot is ExpenseWorkingSlot.Claim && prepared.claim?.id != slot.claimId) ||
                    (slot is ExpenseWorkingSlot.Creation && prepared.claim != null)
            )
                return expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied)
            employee = prepared.employee
            claim = prepared.claim
        } else {
            employee =
                directory?.items?.singleOrNull { it.id == employeeId }
                    ?: return expenseFailure(
                        "client.expense_updates_pending",
                        FailureKind.Unavailable,
                    )
            claim =
                if (slot is ExpenseWorkingSlot.Claim) {
                    val details = replicas.observeDetails(access, slot.claimId).first()
                    currentCoroutineContext().ensureActive()
                    val continuation =
                        validateExpenseContinuation(access, currentAccess(workspaceId))
                    if (continuation is AppResult.Failed) return continuation
                    val snapshot =
                        when (details) {
                            is AppResult.Failed -> return details
                            is AppResult.Success -> details.value.snapshot
                        }
                    val stored = validateStoredExpense(access, snapshot)
                    if (stored is AppResult.Failed) return stored
                    snapshot?.value?.claim
                        ?: return expenseFailure(
                            "client.expense_updates_pending",
                            FailureKind.Unavailable,
                        )
                } else null
        }
        if (
            claim != null &&
                (claim.employeeId != employeeId ||
                    claim.status !in setOf(ExpenseStatus.DRAFT, ExpenseStatus.RETURNED))
        )
            return expenseFailure("expense_not_editable", FailureKind.Conflict)
        return AppResult.Success(
            ExpenseWorkingCopy(
                recoveredCommand?.claimId ?: claim?.id ?: Uuid.random().toString(),
                employee,
                recoveredCommand?.timezone ?: access.timezone,
                ExpenseDraftInput(
                    recovered?.title ?: claim?.draft?.title.orEmpty(),
                    recovered?.description ?: claim?.draft?.description.orEmpty(),
                    recovered?.lines ?: claim?.draft?.lines.orEmpty(),
                    recoveredCommand?.reason.orEmpty(),
                ),
                if (recovered != null) recovered.expectedVersion else claim?.version,
                null,
                Uuid.random().toString(),
                clock.now(),
            )
        )
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
