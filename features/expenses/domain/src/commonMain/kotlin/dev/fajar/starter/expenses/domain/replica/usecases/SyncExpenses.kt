@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.domain.replica.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.entities.*
import dev.fajar.starter.expenses.domain.replica.policies.*
import dev.fajar.starter.expenses.domain.replica.repositories.*
import dev.fajar.starter.expenses.domain.repositories.ExpenseRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.policies.workspaceFailure
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application-owned execution guard covers UI, reconnect, and worker triggers. */
class SyncExpenses(
    private val feed: SyncFeedRepository,
    private val remote: ExpenseRepository,
    private val cache: ExpenseReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val execution: Mutex = Mutex(),
) : SyncCompanyData {
    override val dataSet = CompanyDataSet.Expenses
    override val key = KEY

    override suspend fun refresh(workspaceId: String): AppResult<ExpenseSyncOutcome> =
        invoke(workspaceId)

    suspend operator fun invoke(workspaceId: String): AppResult<ExpenseSyncOutcome> =
        withTimeoutOrNull(30.seconds) { execution.withLock { synchronize(workspaceId) } }
            ?: AppResult.Success(ExpenseSyncOutcome(false, 5))

    override suspend fun invoke(): SyncResult {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value ?: return SyncResult.Complete
            }
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value ?: return SyncResult.Complete
            }
        return when (val result = invoke(workspace.id)) {
            is AppResult.Failed ->
                if (result.failure.code == "sync_scope_changed") SyncResult.Retry(result.failure)
                else syncFailure(result.failure)
            is AppResult.Success ->
                if (result.value.caughtUp) SyncResult.Complete else SyncResult.Retry()
        }
    }

    private suspend fun synchronize(workspaceId: String): AppResult<ExpenseSyncOutcome> {
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val readAdmission =
            when (val result = cache.admission(access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val admissionOwner = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (admissionOwner is AppResult.Failed) return admissionOwner
        val policyRequestedAt = clock.now()
        val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        when (val current = validateExpenseContinuation(access, currentAccess(workspaceId))) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        val policy =
            when (policyResponse) {
                is AppResult.Failed -> return reject(access, policyResponse)
                is AppResult.Success ->
                    CompanyPolicySnapshot(policyResponse.value, policyRequestedAt)
            }
        val availability = admitCompanyOperation(policy, build, CompanyModule.EXPENSES, clock.now())
        if (availability is AppResult.Failed) return reject(access, availability)
        val readAccess = boundExpenseAuthorization(access, policy, clock.now())
        val loaded = loadDirectory(workspaceId, readAccess, policy)
        currentCoroutineContext().ensureActive()
        val directoryOwner = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (directoryOwner is AppResult.Failed) return directoryOwner
        val directory =
            when (loaded) {
                is AppResult.Failed -> return reject(access, loaded)
                is AppResult.Success -> loaded.value
            }
        val synchronized =
            synchronizePages(
                workspaceId,
                readAccess,
                policy,
                directory,
                readAdmission.denial != null,
            )
        currentCoroutineContext().ensureActive()
        val finalOwner = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (finalOwner is AppResult.Failed) return finalOwner
        if (synchronized is AppResult.Failed) return reject(access, synchronized)
        if (!(synchronized as AppResult.Success).value.caughtUp) return synchronized
        return when (val admitted = cache.admit(access, readAdmission.revision)) {
            is AppResult.Failed -> admitted
            is AppResult.Success ->
                if (admitted.value) {
                    val older = synchronizeHistory(workspaceId, readAccess, policy)
                    currentCoroutineContext().ensureActive()
                    val historyOwner =
                        validateExpenseContinuation(access, currentAccess(workspaceId))
                    if (historyOwner is AppResult.Failed) historyOwner
                    else if (older is AppResult.Failed) reject(access, older) else older
                } else
                    when (val current = cache.admission(access)) {
                        is AppResult.Failed -> current
                        is AppResult.Success ->
                            current.value.denial?.let { AppResult.Failed(it) }
                                ?: publicationConflict(workspaceId, access)
                    }
        }
    }

    /** Scope/format replacement keeps owned published data until its successor is complete. */
    private suspend fun prepareCheckpoint(
        workspaceId: String,
        access: ExpenseAccess,
        replacePublished: Boolean,
    ): AppResult<ExpenseCheckpoint?> {
        var checkpoint =
            when (val result = cache.checkpoint(access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val checkpointOwner = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (checkpointOwner is AppResult.Failed) return checkpointOwner
        if (checkpoint != null && !sameStoredExpenseScope(checkpoint.access, access)) {
            val cleared = withTimeoutOrNull(2.seconds) { cache.clear(access) }
            if (cleared !is AppResult.Success || !cleared.value)
                return expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied)
            checkpoint = null
        } else if (
            checkpoint != null &&
                ((replacePublished && checkpoint.published) ||
                    !sameExpenseScope(checkpoint.access, access))
        ) {
            // Reauthentication or an earlier denial rebuilds its published projection.
            // An incomplete replacement keeps its cursor across bounded runs.
            val replacement =
                initialExpenseCheckpoint(access, Uuid.random().toString(), clock.now())
            when (val started = cache.begin(replacement, checkpoint.generation)) {
                is AppResult.Failed -> return started
                is AppResult.Success ->
                    if (!started.value) return publicationConflict(workspaceId, access)
            }
            checkpoint = replacement
        }
        return AppResult.Success(checkpoint)
    }

    private suspend fun synchronizePages(
        workspaceId: String,
        access: ExpenseAccess,
        policy: CompanyPolicySnapshot,
        directory: ExpenseDirectory,
        replacePublished: Boolean,
    ): AppResult<ExpenseSyncOutcome> {
        var checkpoint =
            when (val result = prepareCheckpoint(workspaceId, access, replacePublished)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        var restarted = false
        repeat(10) {
            val ownership = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return ownership
            if (checkpoint == null) {
                val initial =
                    initialExpenseCheckpoint(access, Uuid.random().toString(), clock.now())
                when (val started = cache.begin(initial, null)) {
                    is AppResult.Failed -> return started
                    is AppResult.Success ->
                        if (!started.value) return publicationConflict(workspaceId, access)
                }
                checkpoint = initial
            }
            val previous = requireNotNull(checkpoint)
            previous.pollAt?.let { due ->
                val wait = due - clock.now()
                if (wait.isPositive()) delay(wait)
            }
            val stillAvailable =
                admitCompanyOperation(policy, build, CompanyModule.EXPENSES, clock.now())
            if (stillAvailable is AppResult.Failed) return stillAvailable
            val incoming = nextPage(access, previous)
            currentCoroutineContext().ensureActive()
            val pageOwnership = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (pageOwnership is AppResult.Failed) return pageOwnership
            if (incoming is AppResult.Failed) {
                if (incoming.failure.code in cursorResetCodes && !restarted) {
                    // Cursor retention is unrelated to permission to read stored facts.
                    val replacement =
                        initialExpenseCheckpoint(access, Uuid.random().toString(), clock.now())
                    when (val started = cache.begin(replacement, previous.generation)) {
                        is AppResult.Failed -> return started
                        is AppResult.Success ->
                            if (!started.value) return publicationConflict(workspaceId, access)
                    }
                    checkpoint = replacement
                    restarted = true
                    return@repeat
                }
                return incoming
            }
            val page = (incoming as AppResult.Success).value
            val hydrated = hydrate(workspaceId, access, policy, directory, page.changes)
            currentCoroutineContext().ensureActive()
            val hydrationOwnership = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (hydrationOwnership is AppResult.Failed) return hydrationOwnership
            val entries =
                when (hydrated) {
                    is AppResult.Failed -> return hydrated
                    is AppResult.Success -> hydrated.value
                }
            val complete =
                page.phase == ExpenseSyncPhase.CHANGES && !page.hasMore && !page.pendingPublication
            val finalAvailability =
                admitCompanyOperation(policy, build, CompanyModule.EXPENSES, clock.now())
            if (finalAvailability is AppResult.Failed) return finalAvailability
            val next = advanceExpenseCheckpoint(previous, page, clock.now())
            when (val committed = cache.commit(previous, next, directory, entries)) {
                is AppResult.Failed -> return committed
                is AppResult.Success ->
                    if (!committed.value) return publicationConflict(workspaceId, access)
            }
            checkpoint = next
            if (complete) return AppResult.Success(ExpenseSyncOutcome(true, page.pollAfterSeconds))
        }
        return AppResult.Success(ExpenseSyncOutcome(false, 5))
    }

    private suspend fun nextPage(
        access: ExpenseAccess,
        checkpoint: ExpenseCheckpoint,
    ): AppResult<ExpenseSyncPage> =
        when (checkpoint.phase) {
            ExpenseSyncPhase.BOOTSTRAP ->
                when (
                    val result =
                        feed.bootstrap(
                            access.sessionId,
                            access.companyId,
                            expenseCollections,
                            checkpoint.cursor,
                            limit = 2,
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success -> {
                        val page = result.value
                        if (page.collections != expenseCollections)
                            expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied)
                        else if (
                            (page.nextCursor == null && page.changesCursor == null) ||
                                (page.nextCursor != null && page.nextCursor == checkpoint.cursor)
                        )
                            expenseFailure("client.invalid_response", FailureKind.Unexpected)
                        else
                            AppResult.Success(
                                ExpenseSyncPage(
                                    page.items.map { SyncChange(it, SyncOperation.UPSERT) },
                                    if (page.nextCursor == null) ExpenseSyncPhase.CHANGES
                                    else ExpenseSyncPhase.BOOTSTRAP,
                                    requireNotNull(page.nextCursor ?: page.changesCursor),
                                    true,
                                    false,
                                    5,
                                )
                            )
                    }
                }
            ExpenseSyncPhase.CHANGES ->
                when (
                    val result =
                        feed.changes(
                            access.sessionId,
                            access.companyId,
                            expenseCollections,
                            requireNotNull(checkpoint.cursor),
                            limit = 2,
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        if (result.value.hasMore && result.value.cursor == checkpoint.cursor)
                            expenseFailure("client.invalid_response", FailureKind.Unexpected)
                        else
                            AppResult.Success(
                                ExpenseSyncPage(
                                    result.value.items,
                                    ExpenseSyncPhase.CHANGES,
                                    result.value.cursor,
                                    result.value.hasMore,
                                    result.value.pendingPublication,
                                    result.value.pollAfterSeconds,
                                )
                            )
                }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun hydrate(
        workspaceId: String,
        access: ExpenseAccess,
        policy: CompanyPolicySnapshot,
        directory: ExpenseDirectory,
        changes: List<SyncChange>,
    ): AppResult<List<ExpenseReplicaEntry>> {
        if (
            changes.size > 2 ||
                changes.any {
                    it.reference.collection !in expenseCollections || it.reference.version < 0
                }
        )
            return expenseFailure("client.invalid_response", FailureKind.Unexpected)
        val references = linkedMapOf<Pair<SyncCollection, String>, SyncChange>()
        for (change in changes) {
            val previous = references[change.reference.collection to change.reference.id]
            if (
                previous == null ||
                    change.reference.version > previous.reference.version ||
                    (change.reference.version == previous.reference.version &&
                        change.operation == SyncOperation.DELETE)
            )
                references[change.reference.collection to change.reference.id] = change
        }
        val entries = mutableListOf<ExpenseReplicaEntry>()
        for (batch in references.values.groupBy { it.reference.id }.values.chunked(2)) {
            val results =
                batch
                    .map { group ->
                        flow { emit(hydrateClaim(workspaceId, access, policy, directory, group)) }
                    }
                    .merge()
                    .transformWhile { result ->
                        emit(result)
                        result !is AppResult.Failed
                    }
                    .toList()
            for (result in results) when (result) {
                is AppResult.Failed -> return result
                is AppResult.Success -> entries.addAll(result.value)
            }
        }
        return AppResult.Success(entries.toList())
    }

    /** Every newly published invalidation is hydrated, including equal claim versions. */
    private suspend fun hydrateClaim(
        workspaceId: String,
        access: ExpenseAccess,
        policy: CompanyPolicySnapshot,
        directory: ExpenseDirectory,
        changes: List<SyncChange>,
    ): AppResult<List<ExpenseReplicaEntry>> {
        val claim =
            changes.singleOrNull { it.reference.collection == SyncCollection.EXPENSE_CLAIMS }
        val payment =
            changes.singleOrNull { it.reference.collection == SyncCollection.EXPENSE_PAYMENTS }
        val entries = mutableListOf<ExpenseReplicaEntry>()
        if (claim?.operation == SyncOperation.DELETE) {
            entries += ExpenseReplicaEntry(claim.reference, null)
            if (payment?.operation == SyncOperation.DELETE)
                entries += ExpenseReplicaEntry(payment.reference, null)
            return AppResult.Success(entries.toList())
        }
        val id = changes.first().reference.id
        if (claim != null) {
            when (val loaded = acquireClaim(access, directory, claim.reference)) {
                is AppResult.Failed -> return loaded
                is AppResult.Success -> entries += loaded.value
            }
        }
        val owner = validateExpenseContinuation(access, currentAccess(workspaceId))
        if (owner is AppResult.Failed) return owner
        val available = admitCompanyOperation(policy, build, CompanyModule.EXPENSES, clock.now())
        if (available is AppResult.Failed) return available
        if (payment?.operation == SyncOperation.DELETE)
            entries += ExpenseReplicaEntry(payment.reference, null)
        else {
            val progress =
                when (val loaded = remote.payments(access, id)) {
                    is AppResult.Failed -> return loaded
                    is AppResult.Success -> loaded.value
                }
            currentCoroutineContext().ensureActive()
            if (progress.claimId != id || progress.version < (payment?.reference?.version ?: 0))
                return expenseFailure("client.invalid_response", FailureKind.Unexpected)
            var facts =
                entries
                    .mapNotNull { (it.record as? ExpenseRecord.Claim)?.snapshot?.value }
                    .singleOrNull()
            if (facts == null) {
                val retained =
                    when (val saved = cache.observeDetails(access, id).first()) {
                        is AppResult.Failed -> return saved
                        is AppResult.Success -> saved.value.snapshot?.value
                    }
                val currentOwner = validateExpenseContinuation(access, currentAccess(workspaceId))
                if (currentOwner is AppResult.Failed) return currentOwner
                val currentPolicy =
                    admitCompanyOperation(policy, build, CompanyModule.EXPENSES, clock.now())
                if (currentPolicy is AppResult.Failed) return currentPolicy
                if (
                    retained == null ||
                        (progress.attempts.isNotEmpty() &&
                            retained.claim.status != ExpenseStatus.APPROVED)
                ) {
                    val loaded =
                        when (
                            val result =
                                acquireClaim(
                                    access,
                                    directory,
                                    SyncReference(SyncCollection.EXPENSE_CLAIMS, id, 0),
                                )
                        ) {
                            is AppResult.Failed -> return result
                            is AppResult.Success -> result.value
                        }
                    entries += loaded
                    facts = (loaded.record as ExpenseRecord.Claim).snapshot.value
                }
            }
            if (facts != null) {
                val valid =
                    validateExpenseView(
                        ExpenseView(
                            facts.claim,
                            facts.submission,
                            facts.history,
                            null,
                            progress.attempts,
                        )
                    )
                if (valid is AppResult.Failed) return valid
            }
            entries +=
                ExpenseReplicaEntry(
                    SyncReference(SyncCollection.EXPENSE_PAYMENTS, id, progress.version),
                    ExpenseRecord.Payments(progress),
                )
        }
        return AppResult.Success(entries.toList())
    }

    private suspend fun acquireClaim(
        access: ExpenseAccess,
        directory: ExpenseDirectory,
        reference: SyncReference,
    ): AppResult<ExpenseReplicaEntry> {
        val record =
            when (val loaded = remote.record(access, reference.id, null)) {
                is AppResult.Failed -> return loaded
                is AppResult.Success -> loaded.value
            }
        currentCoroutineContext().ensureActive()
        val valid = validateExpenseClaimRecord(record)
        if (valid is AppResult.Failed) return valid
        if (
            record.claim.id != reference.id ||
                record.historyAfter != null ||
                record.claim.version < reference.version ||
                directory.items.none { it.id == record.claim.employeeId }
        )
            return expenseFailure("client.invalid_response", FailureKind.Unexpected)
        val receivedAt = clock.now()
        return AppResult.Success(
            ExpenseReplicaEntry(
                reference,
                ExpenseRecord.Claim(
                    ExpenseSnapshot(
                        access,
                        Uuid.random().toString(),
                        record,
                        receivedAt,
                        receivedAt + EXPENSE_FRESHNESS_PERIOD,
                    )
                ),
            )
        )
    }

    private suspend fun loadDirectory(
        workspaceId: String,
        access: ExpenseAccess,
        policy: CompanyPolicySnapshot,
    ): AppResult<ExpenseDirectory> {
        var first: ExpenseDirectory? = null
        var after: String? = null
        val items = mutableListOf<ExpenseEmployment>()
        repeat(10) {
            val response = remote.directory(access, after)
            currentCoroutineContext().ensureActive()
            val owner = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (owner is AppResult.Failed) return owner
            val available =
                admitCompanyOperation(policy, build, CompanyModule.EXPENSES, clock.now())
            if (available is AppResult.Failed) return available
            val page =
                when (response) {
                    is AppResult.Failed -> return response
                    is AppResult.Success -> response.value
                }
            if (
                page.accountId != access.accountId ||
                    page.companyId != access.companyId ||
                    page.items.size > EXPENSE_PAGE_SIZE ||
                    (first != null && (page.asOf != first.asOf || page.timezone != first.timezone))
            )
                return expenseFailure("client.invalid_response", FailureKind.Unexpected)
            if (page.timezone != access.timezone)
                return expenseFailure("client.expense_changed", FailureKind.Conflict)
            if (first == null) first = page
            items += page.items
            if (
                items.size > 200 ||
                    items.distinctBy { it.id }.size != items.size ||
                    items.zipWithNext().any { (a, b) -> a.employeeNumber >= b.employeeNumber }
            )
                return expenseFailure("client.invalid_response", FailureKind.Unexpected)
            val next = page.nextCursor
            if (next == null)
                return AppResult.Success(page.copy(items = items.toList(), nextCursor = null))
            if (page.items.isEmpty() || next != page.items.last().employeeNumber || next == after)
                return expenseFailure("client.invalid_response", FailureKind.Unexpected)
            after = next
        }
        return expenseFailure("client.invalid_response", FailureKind.Unexpected)
    }

    /** Immutable older activity does not reacquire or renew the claim's action review. */
    private suspend fun synchronizeHistory(
        workspaceId: String,
        access: ExpenseAccess,
        policy: CompanyPolicySnapshot,
    ): AppResult<ExpenseSyncOutcome> {
        repeat(4) {
            val pending =
                when (val loaded = cache.pendingHistory(access)) {
                    is AppResult.Failed -> return loaded
                    is AppResult.Success -> loaded.value
                }
            currentCoroutineContext().ensureActive()
            val owner = validateExpenseContinuation(access, currentAccess(workspaceId))
            if (owner is AppResult.Failed) return owner
            if (pending.isEmpty()) return AppResult.Success(ExpenseSyncOutcome(true, 5))
            for (work in pending) {
                val available =
                    admitCompanyOperation(policy, build, CompanyModule.EXPENSES, clock.now())
                if (available is AppResult.Failed) return available
                val cached = cache.observeDetails(access, work.claimId, work.after).first()
                currentCoroutineContext().ensureActive()
                val page =
                    when (cached) {
                        is AppResult.Failed -> return cached
                        is AppResult.Success -> cached.value.snapshot?.value?.history
                    }
                        ?: when (val loaded = remote.history(access, work.claimId, work.after)) {
                            is AppResult.Failed -> return loaded
                            is AppResult.Success -> loaded.value
                        }
                currentCoroutineContext().ensureActive()
                val current = validateExpenseContinuation(access, currentAccess(workspaceId))
                if (current is AppResult.Failed) return current
                val admitted =
                    admitCompanyOperation(policy, build, CompanyModule.EXPENSES, clock.now())
                if (admitted is AppResult.Failed) return admitted
                when (
                    val stored =
                        cache.storeHistory(
                            access,
                            work,
                            ExpenseHistoryPage(work.claimId, work.claimVersion, work.after, page),
                        )
                ) {
                    is AppResult.Failed -> return stored
                    is AppResult.Success ->
                        if (!stored.value) return publicationConflict(workspaceId, access)
                }
            }
        }
        return when (val remaining = cache.pendingHistory(access)) {
            is AppResult.Failed -> remaining
            is AppResult.Success ->
                AppResult.Success(ExpenseSyncOutcome(remaining.value.isEmpty(), 5))
        }
    }

    private suspend fun publicationConflict(
        workspaceId: String,
        access: ExpenseAccess,
    ): AppResult.Failed =
        when (val current = validateExpenseContinuation(access, currentAccess(workspaceId))) {
            is AppResult.Failed -> current
            is AppResult.Success -> expenseFailure("client.expense_changed", FailureKind.Conflict)
        }

    private suspend fun reject(access: ExpenseAccess, failure: AppResult.Failed): AppResult.Failed {
        if (expenseFailureInvalidatesCache(failure.failure)) {
            withTimeoutOrNull(2.seconds) { cache.restrict(access, failure.failure) }
            withTimeoutOrNull(2.seconds) { cache.clear(access) }
        }
        return failure
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<ExpenseAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return workspaceFailure(
                            "client.session_changed",
                            FailureKind.Unauthorized,
                        )
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
        return admitExpenses(workspaceId, workspace, latest, build, clock.now())
    }

    companion object {
        const val KEY = "hris-expense-sync"
    }
}

private val expenseCollections =
    setOf(SyncCollection.EXPENSE_CLAIMS, SyncCollection.EXPENSE_PAYMENTS)

private val cursorResetCodes =
    setOf(
        "sync_cursor_expired",
        "sync_cursor_out_of_range",
        "sync_cursor_invalidated",
        "sync_bootstrap_limit",
        "invalid_sync_cursor",
    )
