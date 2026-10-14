@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.domain.replica.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.entities.*
import dev.fajar.starter.expenses.domain.replica.repositories.ExpenseReplicaRepository
import dev.fajar.starter.expenses.domain.repositories.ExpenseRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns a single claim acquisition; read denial can only be cleared by admitted full sync. */
class LoadExpenseClaim(
    private val remote: ExpenseRepository,
    private val cache: ExpenseReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val execution: Mutex = Mutex(),
) {
    suspend operator fun invoke(
        workspaceId: String,
        id: String,
        historyAfter: Long? = null,
        acquisition: ExpenseClaimAcquisition = ExpenseClaimAcquisition.FRESH,
    ): AppResult<Unit> =
        withTimeoutOrNull(30.seconds) {
            val valid = validateExpenseId(id)
            if (valid is AppResult.Failed) return@withTimeoutOrNull valid
            val cursor = validateExpenseHistoryCursor(historyAfter)
            if (cursor is AppResult.Failed) return@withTimeoutOrNull cursor
            val access =
                when (val current = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull current
                    is AppResult.Success -> current.value
                }
            val admitted = readAdmission(workspaceId, access)
            if (admitted is AppResult.Failed) return@withTimeoutOrNull admitted
            if (acquisition == ExpenseClaimAcquisition.LOCAL_FIRST) {
                val saved = cache.observeDetails(access, id, historyAfter).first()
                currentCoroutineContext().ensureActive()
                val owner = validateExpenseContinuation(access, currentAccess(workspaceId))
                if (owner is AppResult.Failed) return@withTimeoutOrNull owner
                val stillAdmitted = readAdmission(workspaceId, access)
                if (stillAdmitted is AppResult.Failed) return@withTimeoutOrNull stillAdmitted
                when (saved) {
                    is AppResult.Failed -> return@withTimeoutOrNull saved
                    is AppResult.Success ->
                        if (saved.value.snapshot != null)
                            return@withTimeoutOrNull validateStoredExpense(
                                access,
                                saved.value.snapshot,
                            )
                }
            }
            execution.withLock { acquire(workspaceId, id, historyAfter, acquisition) }
        } ?: expenseFailure("client.expense_timeout", FailureKind.Timeout)

    private suspend fun acquire(
        workspaceId: String,
        id: String,
        historyAfter: Long?,
        acquisition: ExpenseClaimAcquisition,
    ): AppResult<Unit> {
        val access =
            when (val current = currentAccess(workspaceId)) {
                is AppResult.Failed -> return current
                is AppResult.Success -> current.value
            }
        val admitted = readAdmission(workspaceId, access)
        if (admitted is AppResult.Failed) return admitted
        val generation =
            when (val loaded = cache.claimCheckpoint(access)) {
                is AppResult.Failed -> return loaded
                is AppResult.Success -> loaded.value
            }
        currentCoroutineContext().ensureActive()
        val owner = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (owner is AppResult.Failed) return owner
        val requestedAt = clock.now()
        val response = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        val policyOwner = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (policyOwner is AppResult.Failed) return policyOwner
        val policy =
            when (response) {
                is AppResult.Failed -> return reject(access, response)
                is AppResult.Success -> CompanyPolicySnapshot(response.value, requestedAt)
            }
        val available = continuation(workspaceId, access, policy)
        if (available is AppResult.Failed) return available
        if (historyAfter != null && acquisition == ExpenseClaimAcquisition.LOCAL_FIRST) {
            val saved = cache.observeDetails(access, id).first()
            currentCoroutineContext().ensureActive()
            val head =
                when (saved) {
                    is AppResult.Failed -> return saved
                    is AppResult.Success -> saved.value.snapshot
                }
            val latest = continuation(workspaceId, access, policy)
            if (latest is AppResult.Failed) return latest
            if (head != null) return acquireHistory(workspaceId, access, policy, head, historyAfter)
        }
        return acquireRecord(workspaceId, access, policy, generation, id, historyAfter)
    }

    private suspend fun acquireRecord(
        workspaceId: String,
        access: ExpenseAccess,
        policy: CompanyPolicySnapshot,
        generation: String?,
        id: String,
        historyAfter: Long?,
    ): AppResult<Unit> {
        val loaded = remote.record(access, id, null)
        val receivedAt = clock.now()
        val recordOwner = continuation(workspaceId, access, policy)
        if (recordOwner is AppResult.Failed) return recordOwner
        val record =
            when (loaded) {
                is AppResult.Failed -> return reject(access, loaded)
                is AppResult.Success -> loaded.value
            }
        if (record.claim.id != id || record.historyAfter != null)
            return expenseFailure("client.invalid_response", FailureKind.Unexpected)
        val valid = validateExpenseClaimRecord(record)
        if (valid is AppResult.Failed) return valid
        val loadedPayments = remote.payments(access, id)
        val paymentOwner = continuation(workspaceId, access, policy)
        if (paymentOwner is AppResult.Failed) return paymentOwner
        val progress =
            when (loadedPayments) {
                is AppResult.Failed -> return reject(access, loadedPayments)
                is AppResult.Success -> loadedPayments.value
            }
        if (progress.claimId != id)
            return expenseFailure("client.invalid_response", FailureKind.Unexpected)
        val consistent =
            validateExpenseView(
                ExpenseView(
                    record.claim,
                    record.submission,
                    record.history,
                    null,
                    progress.attempts,
                )
            )
        if (consistent is AppResult.Failed) return consistent
        val history =
            if (historyAfter == null) null
            else {
                val older = remote.history(access, id, historyAfter)
                val historyOwner = continuation(workspaceId, access, policy)
                if (historyOwner is AppResult.Failed) return historyOwner
                val page =
                    when (older) {
                        is AppResult.Failed -> return reject(access, older)
                        is AppResult.Success -> older.value
                    }
                val checked =
                    validateExpenseClaimRecord(
                        record.copy(history = page, historyAfter = historyAfter)
                    )
                if (checked is AppResult.Failed) return checked
                ExpenseHistoryPage(id, record.claim.version, historyAfter, page)
            }
        val publication = continuation(workspaceId, access, policy)
        if (publication is AppResult.Failed) return publication
        val snapshot =
            ExpenseSnapshot(
                boundExpenseAuthorization(
                    (publication as AppResult.Success).value,
                    policy,
                    clock.now(),
                ),
                Uuid.random().toString(),
                record,
                receivedAt,
                receivedAt + EXPENSE_FRESHNESS_PERIOD,
            )
        when (val stored = cache.replaceClaim(access, generation, snapshot, progress, history)) {
            is AppResult.Failed -> return stored
            is AppResult.Success ->
                if (!stored.value)
                    return expenseFailure("client.expense_changed", FailureKind.Conflict)
        }
        val current = continuation(workspaceId, access, policy)
        return if (current is AppResult.Failed) current else AppResult.Success(Unit)
    }

    private suspend fun acquireHistory(
        workspaceId: String,
        access: ExpenseAccess,
        policy: CompanyPolicySnapshot,
        snapshot: ExpenseSnapshot<ExpenseView>,
        after: Long,
    ): AppResult<Unit> {
        val claim = snapshot.value.claim
        if (after > claim.version) return expenseFailure("invalid_page")
        val result = remote.history(access, claim.id, after)
        val owner = continuation(workspaceId, access, policy)
        if (owner is AppResult.Failed) return owner
        val history =
            when (result) {
                is AppResult.Failed -> return reject(access, result)
                is AppResult.Success -> result.value
            }
        val valid =
            validateExpenseView(snapshot.value.copy(history = history, historyAfter = after))
        if (valid is AppResult.Failed) return valid
        val work = ExpenseHistoryWork(claim.id, claim.version, snapshot.generation, after)
        when (
            val stored =
                cache.storeHistory(
                    access,
                    work,
                    ExpenseHistoryPage(claim.id, claim.version, after, history),
                )
        ) {
            is AppResult.Failed -> return stored
            is AppResult.Success ->
                if (!stored.value)
                    return expenseFailure("client.expense_changed", FailureKind.Conflict)
        }
        val current = continuation(workspaceId, access, policy)
        return if (current is AppResult.Failed) current else AppResult.Success(Unit)
    }

    private suspend fun readAdmission(workspaceId: String, access: ExpenseAccess): AppResult<Unit> {
        val result = cache.admission(access)
        currentCoroutineContext().ensureActive()
        val owner = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (owner is AppResult.Failed) return owner
        return when (result) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                result.value.denial?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
        }
    }

    private suspend fun continuation(
        workspaceId: String,
        access: ExpenseAccess,
        policy: CompanyPolicySnapshot,
    ): AppResult<ExpenseAccess> {
        currentCoroutineContext().ensureActive()
        val owner = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (owner is AppResult.Failed) return owner
        val available = admitCompanyOperation(policy, build, CompanyModule.EXPENSES, clock.now())
        if (available is AppResult.Failed) return reject(access, available)
        val admission = readAdmission(workspaceId, access)
        return if (admission is AppResult.Failed) admission else owner
    }

    private suspend fun reject(access: ExpenseAccess, failure: AppResult.Failed): AppResult.Failed {
        currentCoroutineContext().ensureActive()
        if (expenseFailureInvalidatesCache(failure.failure)) {
            withTimeoutOrNull(2.seconds) { cache.restrict(access, failure.failure) }
            withTimeoutOrNull(2.seconds) { cache.clear(access) }
        }
        return failure
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<ExpenseAccess> {
        val session =
            when (val loaded = sessions.current()) {
                is AppResult.Failed -> return loaded
                is AppResult.Success ->
                    loaded.value
                        ?: return expenseFailure("session_revoked", FailureKind.Unauthorized)
            }
        val workspace =
            when (val loaded = workspaces.current(session.id)) {
                is AppResult.Failed -> return loaded
                is AppResult.Success -> loaded.value
            }
        val current =
            when (val loaded = sessions.current()) {
                is AppResult.Failed -> return loaded
                is AppResult.Success -> loaded.value
            }
        currentCoroutineContext().ensureActive()
        return admitExpenses(workspaceId, workspace, current, build, clock.now())
    }
}
