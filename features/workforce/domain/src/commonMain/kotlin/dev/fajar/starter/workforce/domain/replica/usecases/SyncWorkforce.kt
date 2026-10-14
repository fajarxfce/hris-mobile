@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.replica.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.*
import dev.fajar.starter.workforce.domain.replica.entities.*
import dev.fajar.starter.workforce.domain.replica.policies.*
import dev.fajar.starter.workforce.domain.replica.repositories.*
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application-owned execution guard covers UI, reconnect, and worker triggers. */
class SyncWorkforce(
    private val feed: SyncFeedRepository,
    private val remote: WorkforceRecordRepository,
    private val directoryRemote: WorkforceRepository,
    private val cache: WorkforceReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val execution: Mutex = Mutex(),
) : SyncTask {
    override val key = KEY

    suspend operator fun invoke(workspaceId: String): AppResult<WorkforceSyncOutcome> =
        withTimeoutOrNull(30.seconds) { execution.withLock { synchronize(workspaceId) } }
            ?: AppResult.Success(WorkforceSyncOutcome(false, 5))

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

    private suspend fun synchronize(workspaceId: String): AppResult<WorkforceSyncOutcome> {
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
        val admissionOwner = validateWorkforceContinuation(access, currentAccess(workspaceId))
        if (admissionOwner is AppResult.Failed) return admissionOwner
        val policyRequestedAt = clock.now()
        val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        when (val current = validateWorkforceContinuation(access, currentAccess(workspaceId))) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        val policy =
            when (policyResponse) {
                is AppResult.Failed -> return reject(access, policyResponse)
                is AppResult.Success ->
                    CompanyPolicySnapshot(policyResponse.value, policyRequestedAt)
            }
        val availability =
            admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (availability is AppResult.Failed) return reject(access, availability)
        val readAccess = boundWorkforceAuthorization(access, policy, clock.now())
        val loaded = loadDirectory(workspaceId, readAccess, policy)
        currentCoroutineContext().ensureActive()
        val ownership = validateWorkforceContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        val directory =
            when (loaded) {
                is AppResult.Failed -> return reject(access, loaded)
                is AppResult.Success -> loaded.value
            }
        val synchronized = synchronizePages(workspaceId, readAccess, policy, directory)
        currentCoroutineContext().ensureActive()
        val finalOwner = validateWorkforceContinuation(access, currentAccess(workspaceId))
        if (finalOwner is AppResult.Failed) return finalOwner
        if (synchronized is AppResult.Failed) return reject(access, synchronized)
        if (!(synchronized as AppResult.Success).value.caughtUp) return synchronized
        return when (val admitted = cache.admit(access, readAdmission.revision)) {
            is AppResult.Failed -> admitted
            is AppResult.Success ->
                if (admitted.value) synchronized
                else
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
        access: WorkforceAccess,
    ): AppResult<WorkforceCheckpoint?> {
        var checkpoint =
            when (val result = cache.checkpoint(access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val checkpointOwner = validateWorkforceContinuation(access, currentAccess(workspaceId))
        if (checkpointOwner is AppResult.Failed) return checkpointOwner
        if (checkpoint != null && !sameStoredWorkforceScope(checkpoint.access, access)) {
            val cleared = withTimeoutOrNull(2.seconds) { cache.clear(access) }
            if (cleared !is AppResult.Success || !cleared.value)
                return calendarFailure("client.calendar_scope_changed", FailureKind.AccessDenied)
            checkpoint = null
        } else if (checkpoint != null && !sameWorkforceScope(checkpoint.access, access)) {
            // A new login needs its own cursor; owned published facts remain readable.
            val replacement =
                initialWorkforceCheckpoint(access, Uuid.random().toString(), clock.now())
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
    ): AppResult<WorkforceSyncOutcome> {
        var checkpoint =
            when (val result = prepareCheckpoint(workspaceId, access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        var restarted = false
        repeat(10) {
            val ownership = validateWorkforceContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return ownership
            if (checkpoint == null) {
                val initial =
                    initialWorkforceCheckpoint(access, Uuid.random().toString(), clock.now())
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
            if (stillAvailable is AppResult.Failed) return stillAvailable
            val incoming = nextPage(access, previous)
            currentCoroutineContext().ensureActive()
            val pageOwnership = validateWorkforceContinuation(access, currentAccess(workspaceId))
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
                            initialWorkforceCheckpoint(
                                access,
                                Uuid.random().toString(),
                                clock.now(),
                            )
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
                return incoming
            }
            val page = (incoming as AppResult.Success).value
            val hydrated = hydrate(access, directory, page.changes)
            currentCoroutineContext().ensureActive()
            val hydrationOwnership =
                validateWorkforceContinuation(access, currentAccess(workspaceId))
            if (hydrationOwnership is AppResult.Failed) return hydrationOwnership
            val entries =
                when (hydrated) {
                    is AppResult.Failed -> return hydrated
                    is AppResult.Success -> hydrated.value
                }
            val complete =
                page.phase == WorkforceSyncPhase.CHANGES &&
                    !page.hasMore &&
                    !page.pendingPublication
            val finalAvailability =
                admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
            if (finalAvailability is AppResult.Failed) return finalAvailability
            val next = advanceWorkforceCheckpoint(previous, page, clock.now())
            when (val committed = cache.commit(previous, next, directory, entries)) {
                is AppResult.Failed -> return committed
                is AppResult.Success ->
                    if (!committed.value) return publicationConflict(workspaceId, access)
            }
            checkpoint = next
            if (complete)
                return AppResult.Success(WorkforceSyncOutcome(true, page.pollAfterSeconds))
        }
        return AppResult.Success(WorkforceSyncOutcome(false, 5))
    }

    private suspend fun nextPage(
        access: WorkforceAccess,
        checkpoint: WorkforceCheckpoint,
    ): AppResult<WorkforceSyncPage> =
        when (checkpoint.phase) {
            WorkforceSyncPhase.BOOTSTRAP ->
                when (
                    val result =
                        feed.bootstrap(
                            access.sessionId,
                            access.companyId,
                            workforceCollections,
                            checkpoint.cursor,
                            limit = 32,
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success -> {
                        val page = result.value
                        if (page.collections != workforceCollections)
                            calendarFailure(
                                "client.calendar_scope_changed",
                                FailureKind.AccessDenied,
                            )
                        else if (
                            (page.nextCursor == null && page.changesCursor == null) ||
                                (page.nextCursor != null && page.nextCursor == checkpoint.cursor)
                        )
                            calendarFailure("client.invalid_response", FailureKind.Unexpected)
                        else
                            AppResult.Success(
                                WorkforceSyncPage(
                                    page.items.map { SyncChange(it, SyncOperation.UPSERT) },
                                    if (page.nextCursor == null) WorkforceSyncPhase.CHANGES
                                    else WorkforceSyncPhase.BOOTSTRAP,
                                    requireNotNull(page.nextCursor ?: page.changesCursor),
                                    true,
                                    false,
                                    5,
                                )
                            )
                    }
                }
            WorkforceSyncPhase.CHANGES ->
                when (
                    val result =
                        feed.changes(
                            access.sessionId,
                            access.companyId,
                            workforceCollections,
                            requireNotNull(checkpoint.cursor),
                            limit = 32,
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        if (result.value.hasMore && result.value.cursor == checkpoint.cursor)
                            calendarFailure("client.invalid_response", FailureKind.Unexpected)
                        else
                            AppResult.Success(
                                WorkforceSyncPage(
                                    result.value.items,
                                    WorkforceSyncPhase.CHANGES,
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
            val ownership = validateWorkforceContinuation(access, currentAccess(workspaceId))
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
                return calendarFailure("client.invalid_response", FailureKind.Unexpected)
            if (
                directory != null &&
                    (directory.asOf != page.asOf || directory.timezone != page.timezone)
            )
                return calendarFailure("client.calendar_changed", FailureKind.Conflict)
            employees.addAll(page.items)
            if (
                employees.distinctBy { it.id }.size != employees.size ||
                    !employees.zipWithNext().all { (first, next) ->
                        first.employeeNumber < next.employeeNumber
                    }
            )
                return calendarFailure("client.invalid_response", FailureKind.Unexpected)
            directory = page
            if (page.nextCursor == null)
                return AppResult.Success(page.copy(items = employees.toList()))
            if (
                page.items.isEmpty() ||
                    page.nextCursor != page.items.last().employeeNumber ||
                    page.nextCursor == after
            )
                return calendarFailure("client.invalid_response", FailureKind.Unexpected)
            after = page.nextCursor
        }
        return calendarFailure("sync_scope_limit", FailureKind.Validation)
    }

    private suspend fun hydrate(
        access: WorkforceAccess,
        directory: WorkforceDirectory,
        changes: List<SyncChange>,
    ): AppResult<List<WorkforceReplicaEntry>> {
        if (changes.size > 32 || changes.any { it.reference.collection !in workforceCollections })
            return calendarFailure("client.invalid_response", FailureKind.Unexpected)
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
        val entries = mutableListOf<WorkforceReplicaEntry>()
        val employees = directory.items.map { it.id }.toSet()
        for (batch in references.values.chunked(4)) {
            val results = coroutineScope {
                batch
                    .map { change ->
                        async {
                            val reference = change.reference
                            if (change.operation == SyncOperation.DELETE)
                                AppResult.Success(WorkforceReplicaEntry(reference, null))
                            else
                                when (val response = readRecord(access, reference)) {
                                    is AppResult.Failed -> response
                                    is AppResult.Success -> {
                                        val record = response.value
                                        when {
                                            record.id != reference.id ||
                                                record.version < reference.version ->
                                                calendarFailure(
                                                    "client.invalid_response",
                                                    FailureKind.Unexpected,
                                                )
                                            workforceRecordEmployee(record)?.let {
                                                it !in employees
                                            } == true ->
                                                calendarFailure(
                                                    "employee_not_found",
                                                    FailureKind.AccessDenied,
                                                )
                                            else ->
                                                AppResult.Success(
                                                    WorkforceReplicaEntry(
                                                        reference.copy(version = record.version),
                                                        record,
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

    private suspend fun readRecord(
        access: WorkforceAccess,
        reference: SyncReference,
    ): AppResult<WorkforceRecord> =
        when (reference.collection) {
            SyncCollection.ATTENDANCE_DAYS -> remote.attendance(access, reference.id)
            SyncCollection.WORK_SCHEDULES -> remote.schedule(access, reference.id)
            SyncCollection.ROSTER_DAYS -> remote.roster(access, reference.id)
            SyncCollection.WORK_HOLIDAYS -> remote.holiday(access, reference.id)
            else -> calendarFailure("client.invalid_response", FailureKind.Unexpected)
        }

    private suspend fun publicationConflict(
        workspaceId: String,
        access: WorkforceAccess,
    ): AppResult.Failed =
        when (val current = validateWorkforceContinuation(access, currentAccess(workspaceId))) {
            is AppResult.Failed -> current
            is AppResult.Success -> calendarFailure("client.calendar_changed", FailureKind.Conflict)
        }

    private suspend fun reject(
        access: WorkforceAccess,
        failure: AppResult.Failed,
    ): AppResult.Failed {
        if (calendarFailureInvalidatesCache(failure.failure)) {
            withTimeoutOrNull(2.seconds) { cache.restrict(access, failure.failure) }
            withTimeoutOrNull(2.seconds) { cache.clear(access) }
        }
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
        return admitWorkforce(workspaceId, workspace, session, build, clock.now())
    }

    companion object {
        const val KEY = "hris-workforce-sync"
    }
}

private val workforceCollections =
    setOf(
        SyncCollection.ATTENDANCE_DAYS,
        SyncCollection.WORK_SCHEDULES,
        SyncCollection.ROSTER_DAYS,
        SyncCollection.WORK_HOLIDAYS,
    )

private val cursorResetCodes =
    setOf(
        "sync_scope_changed",
        "sync_cursor_expired",
        "sync_cursor_out_of_range",
        "sync_cursor_invalidated",
        "sync_bootstrap_limit",
        "invalid_sync_cursor",
    )
