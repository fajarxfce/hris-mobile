@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.overtime.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.*
import dev.fajar.starter.workforce.domain.policies.sameWorkforceScope
import dev.fajar.starter.workforce.domain.repositories.WorkforceRepository
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.policies.workspaceFailure
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application-owned execution guard covers UI, reconnect, and worker triggers. */
class SyncOvertime(
    private val feed: SyncFeedRepository,
    private val remote: OvertimeRecordRepository,
    private val directoryRemote: WorkforceRepository,
    private val cache: OvertimeReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val execution: Mutex = Mutex(),
) : SyncTask {
    override val key = KEY

    suspend operator fun invoke(workspaceId: String): AppResult<OvertimeSyncOutcome> =
        withTimeoutOrNull(30.seconds) { execution.withLock { synchronize(workspaceId) } }
            ?: AppResult.Success(OvertimeSyncOutcome(false, 5))

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
            is AppResult.Failed -> syncFailure(result.failure)
            is AppResult.Success ->
                if (result.value.caughtUp) SyncResult.Complete else SyncResult.Retry()
        }
    }

    private suspend fun synchronize(workspaceId: String): AppResult<OvertimeSyncOutcome> {
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val policyRequestedAt = clock.now()
        val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        when (val current = validateOvertimeContinuation(access, currentAccess(workspaceId))) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        val policy =
            when (policyResponse) {
                is AppResult.Failed -> {
                    if (overtimeFailureInvalidatesCache(policyResponse.failure)) {
                        withTimeoutOrNull(2.seconds) { cache.clear(access) }
                    }
                    return policyResponse
                }
                is AppResult.Success ->
                    CompanyPolicySnapshot(policyResponse.value, policyRequestedAt)
            }
        val availability =
            admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (availability is AppResult.Failed) return reject(access, availability)
        val readAccess = boundOvertimeAuthorization(access, policy, clock.now())
        val loaded = loadDirectory(workspaceId, readAccess, policy)
        currentCoroutineContext().ensureActive()
        val ownership = validateOvertimeContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        val directory =
            when (loaded) {
                is AppResult.Failed -> return reject(access, loaded)
                is AppResult.Success -> loaded.value
            }
        val records = synchronizePages(workspaceId, readAccess, policy, directory)
        return when (records) {
            is AppResult.Failed -> records
            is AppResult.Success ->
                if (records.value.caughtUp) synchronizeHistory(workspaceId, readAccess, policy)
                else records
        }
    }

    /** Scope/format replacement keeps owned published data until its successor is complete. */
    private suspend fun prepareCheckpoint(
        workspaceId: String,
        access: WorkforceAccess,
    ): AppResult<OvertimeCheckpoint?> {
        var checkpoint =
            when (val result = cache.checkpoint(access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val checkpointOwner = validateOvertimeContinuation(access, currentAccess(workspaceId))
        if (checkpointOwner is AppResult.Failed) return checkpointOwner
        if (checkpoint != null && !sameStoredOvertimeScope(checkpoint.access, access)) {
            val cleared = withTimeoutOrNull(2.seconds) { cache.clear(access) }
            if (cleared !is AppResult.Success || !cleared.value) return staleOvertimeScope()
            checkpoint = null
        } else if (checkpoint != null && !sameWorkforceScope(checkpoint.access, access)) {
            // A new login needs its own cursor; owned published facts remain readable.
            val replacement =
                initialOvertimeCheckpoint(access, Uuid.random().toString(), clock.now())
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
        access: WorkforceAccess,
        policy: CompanyPolicySnapshot,
        directory: WorkforceDirectory,
    ): AppResult<OvertimeSyncOutcome> {
        var checkpoint =
            when (val result = prepareCheckpoint(workspaceId, access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        var restarted = false
        repeat(10) {
            val ownership = validateOvertimeContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return ownership
            if (checkpoint == null) {
                val initial =
                    initialOvertimeCheckpoint(access, Uuid.random().toString(), clock.now())
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
                admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
            if (stillAvailable is AppResult.Failed) return reject(access, stillAvailable)
            val incoming = nextPage(access, previous)
            currentCoroutineContext().ensureActive()
            val pageOwnership = validateOvertimeContinuation(access, currentAccess(workspaceId))
            if (pageOwnership is AppResult.Failed) return pageOwnership
            if (incoming is AppResult.Failed) {
                if (incoming.failure.code in cursorResetCodes && !restarted) {
                    if (incoming.failure.code == "sync_scope_changed") {
                        // Failed cleanup cannot turn this scope denial into a storage-only error.
                        val cleared = withTimeoutOrNull(2.seconds) { cache.clear(access) }
                        if (cleared !is AppResult.Success || !cleared.value) return incoming
                        checkpoint = null
                    } else {
                        // Cursor retention is unrelated to permission to read stored facts.
                        // Stage a replacement while the published generation stays visible.
                        val replacement =
                            initialOvertimeCheckpoint(access, Uuid.random().toString(), clock.now())
                        when (val started = cache.begin(replacement, previous.generation)) {
                            is AppResult.Failed -> return started
                            is AppResult.Success ->
                                if (!started.value) return publicationConflict(workspaceId, access)
                        }
                        checkpoint = replacement
                    }
                    restarted = true
                    return@repeat
                }
                if (overtimeFailureInvalidatesCache(incoming.failure)) {
                    withTimeoutOrNull(2.seconds) { cache.clear(access) }
                }
                return incoming
            }
            val page = (incoming as AppResult.Success).value
            val hydrated = hydrate(access, directory, page.changes)
            currentCoroutineContext().ensureActive()
            val hydrationOwnership =
                validateOvertimeContinuation(access, currentAccess(workspaceId))
            if (hydrationOwnership is AppResult.Failed) return hydrationOwnership
            val entries =
                when (hydrated) {
                    is AppResult.Failed -> {
                        if (overtimeFailureInvalidatesCache(hydrated.failure)) {
                            withTimeoutOrNull(2.seconds) { cache.clear(access) }
                        }
                        return hydrated
                    }
                    is AppResult.Success -> hydrated.value
                }
            val complete =
                page.phase == OvertimeSyncPhase.CHANGES && !page.hasMore && !page.pendingPublication
            val finalAvailability =
                admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
            if (finalAvailability is AppResult.Failed) return reject(access, finalAvailability)
            val next = advanceOvertimeCheckpoint(previous, page, clock.now())
            when (val committed = cache.commit(previous, next, directory, entries)) {
                is AppResult.Failed -> return committed
                is AppResult.Success ->
                    if (!committed.value) return publicationConflict(workspaceId, access)
            }
            checkpoint = next
            if (complete) return AppResult.Success(OvertimeSyncOutcome(true, page.pollAfterSeconds))
        }
        return AppResult.Success(OvertimeSyncOutcome(false, 5))
    }

    private suspend fun synchronizeHistory(
        workspaceId: String,
        access: WorkforceAccess,
        policy: CompanyPolicySnapshot,
    ): AppResult<OvertimeSyncOutcome> {
        val pending = cache.pendingHistory(access, 8)
        currentCoroutineContext().ensureActive()
        val owner = validateOvertimeContinuation(access, currentAccess(workspaceId))
        if (owner is AppResult.Failed) return owner
        val work =
            when (pending) {
                is AppResult.Failed -> return reject(access, pending)
                is AppResult.Success -> pending.value
            }
        for (item in work) {
            val available =
                admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
            if (available is AppResult.Failed) return reject(access, available)
            val acquired = acquireHistoryPage(workspaceId, access, item)
            currentCoroutineContext().ensureActive()
            val ownership = validateOvertimeContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return ownership
            val page =
                when (acquired) {
                    is AppResult.Failed -> return reject(access, acquired)
                    is AppResult.Success ->
                        acquired.value ?: return AppResult.Success(OvertimeSyncOutcome(false, 5))
                }
            val next = page.history.nextCursor?.toLongOrNull()
            if (page.history.nextCursor != null && (next == null || next !in 0 until item.after))
                return overtimeFailure("client.invalid_response", FailureKind.Unexpected)
            val stillAvailable =
                admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
            if (stillAvailable is AppResult.Failed) return reject(access, stillAvailable)
            val stored = cache.storeHistory(access, item, page)
            currentCoroutineContext().ensureActive()
            val committedOwner = validateOvertimeContinuation(access, currentAccess(workspaceId))
            if (committedOwner is AppResult.Failed) return committedOwner
            when (stored) {
                is AppResult.Failed -> return reject(access, stored)
                is AppResult.Success ->
                    if (!stored.value) return AppResult.Success(OvertimeSyncOutcome(false, 5))
            }
        }
        val remaining = cache.pendingHistory(access, 1)
        currentCoroutineContext().ensureActive()
        val current = validateOvertimeContinuation(access, currentAccess(workspaceId))
        if (current is AppResult.Failed) return current
        return when (remaining) {
            is AppResult.Failed -> remaining
            is AppResult.Success ->
                AppResult.Success(OvertimeSyncOutcome(remaining.value.isEmpty(), 5))
        }
    }

    /** A newer request must first reach the local change-feed projection. */
    private suspend fun acquireHistoryPage(
        workspaceId: String,
        access: WorkforceAccess,
        work: OvertimeHistoryWork,
    ): AppResult<OvertimeHistoryPage?> {
        val selection =
            OvertimeRequestSelection(work.requestId, work.employeeId, historyAfter = work.after)
        val local = cache.observeRequest(access, selection).first()
        currentCoroutineContext().ensureActive()
        val owner = validateOvertimeContinuation(access, currentAccess(workspaceId))
        if (owner is AppResult.Failed) return owner
        when (local) {
            is AppResult.Failed -> return local
            is AppResult.Success ->
                local.value?.let { saved ->
                    return AppResult.Success(
                        if (saved.value.details.request.version == work.requestVersion)
                            OvertimeHistoryPage(
                                work.requestId,
                                work.requestVersion,
                                work.after,
                                saved.value.details.history,
                            )
                        else null
                    )
                }
        }
        val response = remote.request(access, work.requestId, work.after)
        currentCoroutineContext().ensureActive()
        val ownership = validateOvertimeContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        val details =
            when (response) {
                is AppResult.Failed -> return response
                is AppResult.Success -> response.value
            }
        val request = details.request
        if (
            request.id != work.requestId ||
                request.employeeId != work.employeeId ||
                request.version < work.requestVersion
        )
            return overtimeFailure("client.invalid_response", FailureKind.Unexpected)
        if (request.version > work.requestVersion) {
            delay(5.seconds)
            return AppResult.Success(null)
        }
        return AppResult.Success(
            OvertimeHistoryPage(work.requestId, work.requestVersion, work.after, details.history)
        )
    }

    private suspend fun nextPage(
        access: WorkforceAccess,
        checkpoint: OvertimeCheckpoint,
    ): AppResult<OvertimeSyncPage> =
        when (checkpoint.phase) {
            OvertimeSyncPhase.BOOTSTRAP ->
                when (
                    val result =
                        feed.bootstrap(
                            access.sessionId,
                            access.companyId,
                            overtimeCollections,
                            checkpoint.cursor,
                            limit = 8,
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success -> {
                        val page = result.value
                        if (page.collections != overtimeCollections) staleOvertimeScope()
                        else
                            AppResult.Success(
                                OvertimeSyncPage(
                                    page.items.map { SyncChange(it, SyncOperation.UPSERT) },
                                    if (page.nextCursor == null) OvertimeSyncPhase.CHANGES
                                    else OvertimeSyncPhase.BOOTSTRAP,
                                    requireNotNull(page.nextCursor ?: page.changesCursor),
                                    true,
                                    false,
                                    5,
                                )
                            )
                    }
                }
            OvertimeSyncPhase.CHANGES ->
                when (
                    val result =
                        feed.changes(
                            access.sessionId,
                            access.companyId,
                            overtimeCollections,
                            requireNotNull(checkpoint.cursor),
                            limit = 8,
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(
                            OvertimeSyncPage(
                                result.value.items,
                                OvertimeSyncPhase.CHANGES,
                                result.value.cursor,
                                result.value.hasMore,
                                result.value.pendingPublication,
                                result.value.pollAfterSeconds,
                            )
                        )
                }
        }

    private suspend fun loadDirectory(
        workspaceId: String,
        access: WorkforceAccess,
        policy: CompanyPolicySnapshot,
    ): AppResult<WorkforceDirectory> {
        var directory: WorkforceDirectory? = null
        var after: String? = null
        val employees = mutableListOf<WorkforceEmployment>()
        repeat(10) {
            val available =
                admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
            if (available is AppResult.Failed) return available
            val response = directoryRemote.selfEmployments(access, after)
            currentCoroutineContext().ensureActive()
            val ownership = validateOvertimeContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return ownership
            val page =
                when (response) {
                    is AppResult.Failed -> return response
                    is AppResult.Success -> response.value
                }
            if (
                page.accountId != access.accountId ||
                    page.companyId != access.companyId ||
                    page.items.size > 20 ||
                    page.items.any { after != null && it.employeeNumber <= after!! }
            )
                return overtimeFailure("client.invalid_response", FailureKind.Unexpected)
            if (
                directory != null &&
                    (directory.asOf != page.asOf || directory.timezone != page.timezone)
            )
                return overtimeFailure("client.overtime_changed", FailureKind.Conflict)
            employees.addAll(page.items)
            if (
                employees.distinctBy { it.id }.size != employees.size ||
                    !employees.zipWithNext().all { (first, next) ->
                        first.employeeNumber < next.employeeNumber
                    }
            )
                return overtimeFailure("client.invalid_response", FailureKind.Unexpected)
            directory = page
            if (page.nextCursor == null)
                return AppResult.Success(page.copy(items = employees.toList()))
            if (
                page.items.isEmpty() ||
                    page.nextCursor != page.items.last().employeeNumber ||
                    page.nextCursor == after
            )
                return overtimeFailure("client.invalid_response", FailureKind.Unexpected)
            after = page.nextCursor
        }
        return overtimeFailure("sync_scope_limit", FailureKind.Validation)
    }

    private suspend fun hydrate(
        access: WorkforceAccess,
        directory: WorkforceDirectory,
        changes: List<SyncChange>,
    ): AppResult<List<OvertimeReplicaEntry>> {
        if (changes.size > 8 || changes.any { it.reference.collection !in overtimeCollections })
            return overtimeFailure("client.invalid_response", FailureKind.Unexpected)
        val references = linkedMapOf<String, SyncChange>()
        for (change in changes) {
            val previous = references[change.reference.id]
            if (
                previous == null ||
                    change.reference.version > previous.reference.version ||
                    (change.reference.version == previous.reference.version &&
                        change.operation == SyncOperation.DELETE)
            )
                references[change.reference.id] = change
        }
        val entries = mutableListOf<OvertimeReplicaEntry>()
        val employees = directory.items.map { it.id }.toSet()
        for (batch in references.values.chunked(4)) {
            val results = coroutineScope {
                batch
                    .map { change ->
                        async {
                            val reference = change.reference
                            if (change.operation == SyncOperation.DELETE)
                                AppResult.Success(OvertimeReplicaEntry(reference, null))
                            else
                                when (val response = remote.request(access, reference.id)) {
                                    is AppResult.Failed -> response
                                    is AppResult.Success -> {
                                        val record = response.value
                                        val request = record.request
                                        when {
                                            request.id != reference.id ||
                                                request.version < reference.version ->
                                                overtimeFailure(
                                                    "client.invalid_response",
                                                    FailureKind.Unexpected,
                                                )
                                            request.employeeId !in employees ->
                                                overtimeFailure(
                                                    "employee_not_found",
                                                    FailureKind.AccessDenied,
                                                )
                                            else ->
                                                AppResult.Success(
                                                    OvertimeReplicaEntry(
                                                        reference.copy(version = request.version),
                                                        record,
                                                        clock.now(),
                                                    )
                                                )
                                        }
                                    }
                                }
                        }
                    }
                    .awaitAll()
            }
            for (result in results) when (result) {
                is AppResult.Failed -> return result
                is AppResult.Success -> entries.add(result.value)
            }
        }
        return AppResult.Success(entries.toList())
    }

    private suspend fun publicationConflict(
        workspaceId: String,
        access: WorkforceAccess,
    ): AppResult.Failed =
        when (val current = validateOvertimeContinuation(access, currentAccess(workspaceId))) {
            is AppResult.Failed -> current
            is AppResult.Success -> overtimeFailure("client.overtime_changed", FailureKind.Conflict)
        }

    private suspend fun reject(
        access: WorkforceAccess,
        failure: AppResult.Failed,
    ): AppResult.Failed {
        if (overtimeFailureInvalidatesCache(failure.failure))
            withTimeoutOrNull(2.seconds) { cache.clear(access) }
        return failure
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<WorkforceAccess> {
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
        return admitOvertime(workspaceId, workspace, session, build, clock.now())
    }

    companion object {
        const val KEY = "hris-overtime-sync"
    }
}

private fun staleOvertimeScope() =
    AppResult.Failed(
        Failure(
            FailureKind.AccessDenied,
            "Refresh the overtime.",
            code = "client.overtime_scope_changed",
        )
    )

private val overtimeCollections = setOf(SyncCollection.OVERTIME_REQUESTS)

private val cursorResetCodes =
    setOf(
        "sync_scope_changed",
        "sync_cursor_expired",
        "sync_cursor_out_of_range",
        "sync_cursor_invalidated",
        "sync_bootstrap_limit",
        "invalid_sync_cursor",
    )
