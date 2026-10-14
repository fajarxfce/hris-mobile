@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.people.domain.profile.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.people.domain.profile.entities.*
import dev.fajar.starter.people.domain.profile.policies.*
import dev.fajar.starter.people.domain.profile.repositories.*
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.policies.workspaceFailure
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application-owned execution guard covers UI, reconnect, and worker triggers. */
class SyncSelfProfiles(
    private val feed: SyncFeedRepository,
    private val remote: SelfProfileRecordRepository,
    private val cache: SelfProfileReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val execution: Mutex = Mutex(),
) : SyncTask {
    override val key = KEY

    suspend operator fun invoke(
        workspaceId: String,
        force: Boolean = true,
    ): AppResult<SelfProfileSyncOutcome> =
        withTimeoutOrNull(30.seconds) { execution.withLock { synchronize(workspaceId, force) } }
            ?: AppResult.Success(SelfProfileSyncOutcome(false, 5))

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

    private suspend fun synchronize(
        workspaceId: String,
        force: Boolean,
    ): AppResult<SelfProfileSyncOutcome> {
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
        val admissionOwner = validateSelfProfileContinuation(access, currentAccess(workspaceId))
        if (admissionOwner is AppResult.Failed) return admissionOwner
        if (!force && readAdmission.denial == null) {
            val retained =
                when (val result = cache.checkpoint(access)) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
            currentCoroutineContext().ensureActive()
            val currentOwner = validateSelfProfileContinuation(access, currentAccess(workspaceId))
            if (currentOwner is AppResult.Failed) return currentOwner
            if (
                retained != null &&
                    retained.published &&
                    retained.access == access &&
                    clock.now() < retained.refreshAfter
            )
                return AppResult.Success(SelfProfileSyncOutcome(true, 5))
        }
        val policyRequestedAt = clock.now()
        val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        when (val current = validateSelfProfileContinuation(access, currentAccess(workspaceId))) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        val policy =
            when (policyResponse) {
                is AppResult.Failed -> return reject(access, policyResponse)
                is AppResult.Success ->
                    CompanyPolicySnapshot(policyResponse.value, policyRequestedAt)
            }
        val availability = admitCompanyOperation(policy, build, CompanyModule.PEOPLE, clock.now())
        if (availability is AppResult.Failed) return reject(access, availability)
        val readAccess = access
        val synchronized =
            synchronizePages(workspaceId, readAccess, policy, readAdmission.denial != null)
        currentCoroutineContext().ensureActive()
        val finalOwner = validateSelfProfileContinuation(access, currentAccess(workspaceId))
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
        access: SelfProfileAccess,
        replacePublished: Boolean,
    ): AppResult<SelfProfileCheckpoint?> {
        var checkpoint =
            when (val result = cache.checkpoint(access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val checkpointOwner = validateSelfProfileContinuation(access, currentAccess(workspaceId))
        if (checkpointOwner is AppResult.Failed) return checkpointOwner
        if (checkpoint != null && !sameStoredSelfProfileScope(checkpoint.access, access)) {
            val cleared = withTimeoutOrNull(2.seconds) { cache.clear(access) }
            if (cleared !is AppResult.Success || !cleared.value)
                return selfProfileFailure(
                    "client.self_profile_scope_changed",
                    FailureKind.AccessDenied,
                )
            checkpoint = null
        } else if (
            checkpoint != null &&
                ((replacePublished && checkpoint.published) || checkpoint.access != access)
        ) {
            // Reauthentication or an earlier denial rebuilds its published projection.
            // An incomplete replacement keeps its cursor across bounded runs.
            val replacement =
                initialSelfProfileCheckpoint(access, Uuid.random().toString(), clock.now())
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
        access: SelfProfileAccess,
        policy: CompanyPolicySnapshot,
        replacePublished: Boolean,
    ): AppResult<SelfProfileSyncOutcome> {
        var checkpoint =
            when (val result = prepareCheckpoint(workspaceId, access, replacePublished)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        var restarted = false
        repeat(10) {
            val ownership = validateSelfProfileContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return ownership
            if (checkpoint == null) {
                val initial =
                    initialSelfProfileCheckpoint(access, Uuid.random().toString(), clock.now())
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
                admitCompanyOperation(policy, build, CompanyModule.PEOPLE, clock.now())
            if (stillAvailable is AppResult.Failed) return stillAvailable
            val incoming = nextPage(access, previous)
            currentCoroutineContext().ensureActive()
            val pageOwnership = validateSelfProfileContinuation(access, currentAccess(workspaceId))
            if (pageOwnership is AppResult.Failed) return pageOwnership
            if (incoming is AppResult.Failed) {
                if (incoming.failure.code in cursorResetCodes && !restarted) {
                    // Cursor retention is unrelated to permission to read stored facts.
                    val replacement =
                        initialSelfProfileCheckpoint(access, Uuid.random().toString(), clock.now())
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
            val hydrated = hydrate(workspaceId, access, policy, page.changes)
            currentCoroutineContext().ensureActive()
            val hydrationOwnership =
                validateSelfProfileContinuation(access, currentAccess(workspaceId))
            if (hydrationOwnership is AppResult.Failed) return hydrationOwnership
            val entries =
                when (hydrated) {
                    is AppResult.Failed -> return hydrated
                    is AppResult.Success -> hydrated.value
                }
            val complete =
                page.phase == SelfProfileSyncPhase.CHANGES &&
                    !page.hasMore &&
                    !page.pendingPublication
            val finalAvailability =
                admitCompanyOperation(policy, build, CompanyModule.PEOPLE, clock.now())
            if (finalAvailability is AppResult.Failed) return finalAvailability
            val next = advanceSelfProfileCheckpoint(previous, page, clock.now())
            when (val committed = cache.commit(previous, next, entries)) {
                is AppResult.Failed -> return committed
                is AppResult.Success ->
                    if (!committed.value) return publicationConflict(workspaceId, access)
            }
            checkpoint = next
            if (complete)
                return AppResult.Success(SelfProfileSyncOutcome(true, page.pollAfterSeconds))
        }
        return AppResult.Success(SelfProfileSyncOutcome(false, 5))
    }

    private suspend fun nextPage(
        access: SelfProfileAccess,
        checkpoint: SelfProfileCheckpoint,
    ): AppResult<SelfProfileSyncPage> =
        when (checkpoint.phase) {
            SelfProfileSyncPhase.BOOTSTRAP ->
                when (
                    val result =
                        feed.bootstrap(
                            access.sessionId,
                            access.companyId,
                            selfProfileCollections,
                            checkpoint.cursor,
                            limit = 50,
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success -> {
                        val page = result.value
                        if (page.collections != selfProfileCollections)
                            selfProfileFailure(
                                "client.self_profile_scope_changed",
                                FailureKind.AccessDenied,
                            )
                        else if (
                            (page.nextCursor == null && page.changesCursor == null) ||
                                (page.nextCursor != null && page.nextCursor == checkpoint.cursor)
                        )
                            selfProfileFailure("client.invalid_response", FailureKind.Unexpected)
                        else
                            AppResult.Success(
                                SelfProfileSyncPage(
                                    page.items.map { SyncChange(it, SyncOperation.UPSERT) },
                                    if (page.nextCursor == null) SelfProfileSyncPhase.CHANGES
                                    else SelfProfileSyncPhase.BOOTSTRAP,
                                    requireNotNull(page.nextCursor ?: page.changesCursor),
                                    true,
                                    false,
                                    5,
                                )
                            )
                    }
                }
            SelfProfileSyncPhase.CHANGES ->
                when (
                    val result =
                        feed.changes(
                            access.sessionId,
                            access.companyId,
                            selfProfileCollections,
                            requireNotNull(checkpoint.cursor),
                            limit = 50,
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        if (result.value.hasMore && result.value.cursor == checkpoint.cursor)
                            selfProfileFailure("client.invalid_response", FailureKind.Unexpected)
                        else
                            AppResult.Success(
                                SelfProfileSyncPage(
                                    result.value.items,
                                    SelfProfileSyncPhase.CHANGES,
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
        access: SelfProfileAccess,
        policy: CompanyPolicySnapshot,
        changes: List<SyncChange>,
    ): AppResult<List<SelfProfileReplicaEntry>> {
        if (
            changes.size > 50 ||
                changes.any {
                    it.reference.collection != SyncCollection.SELF_PROFILES ||
                        it.reference.version < 0 ||
                        Uuid.parseOrNull(it.reference.id)?.toString() != it.reference.id
                }
        )
            return selfProfileFailure("client.invalid_response", FailureKind.Unexpected)
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
        val entries = mutableListOf<SelfProfileReplicaEntry>()
        for (batch in references.values.chunked(4)) {
            val owner = validateSelfProfileContinuation(access, currentAccess(workspaceId))
            if (owner is AppResult.Failed) return owner
            val available = admitCompanyOperation(policy, build, CompanyModule.PEOPLE, clock.now())
            if (available is AppResult.Failed) return available
            val results =
                batch
                    .map { change ->
                        flow {
                            if (change.operation == SyncOperation.DELETE) {
                                emit(
                                    AppResult.Success(
                                        SelfProfileReplicaEntry(change.reference, null)
                                    )
                                )
                            } else {
                                val loaded = remote.record(access, change.reference.id)
                                currentCoroutineContext().ensureActive()
                                emit(
                                    when (loaded) {
                                        is AppResult.Failed ->
                                            // This owned record may have been withdrawn
                                            // after its reference.
                                            if (loaded.failure.code == "self_profile_not_found")
                                                AppResult.Success(
                                                    SelfProfileReplicaEntry(change.reference, null)
                                                )
                                            else loaded
                                        is AppResult.Success -> {
                                            val value = loaded.value
                                            if (
                                                value.id != change.reference.id ||
                                                    value.version < change.reference.version ||
                                                    value.profile.details.employee.id != value.id ||
                                                    value.profile.details.employee.companyId !=
                                                        access.companyId ||
                                                    value.profile.details.employee.accountId !=
                                                        access.accountId ||
                                                    value.profile.person.accountId !=
                                                        access.accountId ||
                                                    value.profile.person.personId !=
                                                        value.profile.details.employee.personId
                                            )
                                                selfProfileFailure(
                                                    "client.invalid_response",
                                                    FailureKind.Unexpected,
                                                )
                                            else
                                                AppResult.Success(
                                                    SelfProfileReplicaEntry(
                                                        change.reference.copy(
                                                            version = value.version
                                                        ),
                                                        value,
                                                    )
                                                )
                                        }
                                    }
                                )
                            }
                        }
                    }
                    .merge()
                    .transformWhile { result ->
                        emit(result)
                        result !is AppResult.Failed
                    }
                    .toList()
            for (result in results) when (result) {
                is AppResult.Failed -> return result
                is AppResult.Success -> entries += result.value
            }
        }
        return AppResult.Success(entries.toList())
    }

    private suspend fun publicationConflict(
        workspaceId: String,
        access: SelfProfileAccess,
    ): AppResult.Failed =
        when (val current = validateSelfProfileContinuation(access, currentAccess(workspaceId))) {
            is AppResult.Failed -> current
            is AppResult.Success ->
                selfProfileFailure("client.self_profile_changed", FailureKind.Conflict)
        }

    private suspend fun reject(
        access: SelfProfileAccess,
        failure: AppResult.Failed,
    ): AppResult.Failed {
        if (selfProfileFailureClosesRead(failure.failure)) {
            withTimeoutOrNull(2.seconds) { cache.restrict(access, failure.failure) }
            withTimeoutOrNull(2.seconds) { cache.clear(access) }
        }
        return failure
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<SelfProfileAccess> {
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
        return admitSelfProfile(workspaceId, workspace, latest, build, clock.now())
    }

    companion object {
        const val KEY = "hris-self-profile-sync"
    }
}

private val selfProfileCollections = setOf(SyncCollection.SELF_PROFILES)

private val cursorResetCodes =
    setOf(
        "sync_cursor_expired",
        "sync_cursor_out_of_range",
        "sync_cursor_invalidated",
        "sync_bootstrap_limit",
        "invalid_sync_cursor",
    )
