@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.inbox.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.*
import dev.fajar.starter.inbox.domain.repositories.*
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application-owned execution guard covers UI, reconnect, and worker triggers. */
class SyncInbox(
    private val feed: SyncFeedRepository,
    private val remote: InboxRepository,
    private val cache: InboxCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) : SyncTask {
    override val key = KEY
    private val execution = Mutex()

    suspend operator fun invoke(workspaceId: String): AppResult<InboxSyncOutcome> =
        execution.withLock {
            withTimeoutOrNull(30.seconds) { synchronize(workspaceId) }
                ?: AppResult.Success(InboxSyncOutcome(false, 5))
        }

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

    private suspend fun synchronize(workspaceId: String): AppResult<InboxSyncOutcome> {
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val admission = cache.admission(access)
        currentCoroutineContext().ensureActive()
        val admissionOwner = validateInboxCommandAccess(currentAccess(workspaceId), access)
        if (admissionOwner is AppResult.Failed) return admissionOwner
        val readAdmission =
            when (admission) {
                is AppResult.Failed -> return admission
                is AppResult.Success -> admission.value
            }
        val policyRequestedAt = clock.now()
        val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        when (val current = validateInboxCommandAccess(currentAccess(workspaceId), access)) {
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
            admitCompanyOperation(policy, build, CompanyModule.COMMUNICATIONS, clock.now())
        if (availability is AppResult.Failed) return reject(access, availability)
        val result = synchronizePages(workspaceId, access, policy)
        currentCoroutineContext().ensureActive()
        val owner = validateInboxCommandAccess(currentAccess(workspaceId), access)
        if (owner is AppResult.Failed) return owner
        if (result is AppResult.Failed) return reject(access, result)
        if (!(result as AppResult.Success).value.caughtUp) return result
        val available =
            admitCompanyOperation(policy, build, CompanyModule.COMMUNICATIONS, clock.now())
        if (available is AppResult.Failed) return reject(access, available)
        val reopened = cache.admit(access, readAdmission.revision)
        currentCoroutineContext().ensureActive()
        val finalOwner = validateInboxCommandAccess(currentAccess(workspaceId), access)
        if (finalOwner is AppResult.Failed) return finalOwner
        return when (reopened) {
            is AppResult.Failed -> reopened
            is AppResult.Success ->
                if (reopened.value) result
                else {
                    val latest = cache.admission(access)
                    currentCoroutineContext().ensureActive()
                    val current = validateInboxCommandAccess(currentAccess(workspaceId), access)
                    if (current is AppResult.Failed) current
                    else
                        when (latest) {
                            is AppResult.Failed -> latest
                            is AppResult.Success ->
                                latest.value.denial?.let { AppResult.Failed(it) }
                                    ?: staleInboxScope()
                        }
                }
        }
    }

    private suspend fun synchronizePages(
        workspaceId: String,
        access: InboxAccess,
        policy: CompanyPolicySnapshot,
    ): AppResult<InboxSyncOutcome> {
        var checkpoint =
            when (val result = cache.checkpoint(access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (checkpoint != null && !sameStoredInboxScope(checkpoint.access, access)) {
            return staleInboxScope()
        } else if (checkpoint != null && !sameInboxScope(checkpoint.access, access)) {
            // A replacement login needs its own cursor, while the same owner's facts stay visible.
            val replacement = initialInboxCheckpoint(access, Uuid.random().toString(), clock.now())
            when (val started = cache.begin(replacement, checkpoint.generation)) {
                is AppResult.Failed -> return started
                is AppResult.Success -> if (!started.value) return staleInboxScope()
            }
            checkpoint = replacement
        }
        var restarted = false
        repeat(10) {
            val ownership = currentAccess(workspaceId)
            if (ownership is AppResult.Failed) return ownership
            if (!sameInboxScope((ownership as AppResult.Success).value, access))
                return staleInboxScope()
            if (checkpoint == null) {
                val initial = initialInboxCheckpoint(access, Uuid.random().toString(), clock.now())
                when (val started = cache.begin(initial, null)) {
                    is AppResult.Failed -> return started
                    is AppResult.Success -> if (!started.value) return staleInboxScope()
                }
                checkpoint = initial
            }
            val previous = requireNotNull(checkpoint)
            previous.pollAt?.let { due ->
                val wait = due - clock.now()
                if (wait.isPositive()) delay(wait)
            }
            val stillAvailable =
                admitCompanyOperation(policy, build, CompanyModule.COMMUNICATIONS, clock.now())
            if (stillAvailable is AppResult.Failed) return stillAvailable
            val incoming = nextPage(access, previous)
            currentCoroutineContext().ensureActive()
            when (val current = currentAccess(workspaceId)) {
                is AppResult.Failed -> return current
                is AppResult.Success ->
                    if (!sameInboxScope(current.value, access)) return staleInboxScope()
            }
            if (incoming is AppResult.Failed) {
                if (incoming.failure.code == "sync_scope_changed") return incoming
                if (incoming.failure.code in cursorResetCodes && !restarted) {
                    // Cursor retention does not revoke locally owned facts.
                    val replacement =
                        initialInboxCheckpoint(access, Uuid.random().toString(), clock.now())
                    when (val started = cache.begin(replacement, previous.generation)) {
                        is AppResult.Failed -> return started
                        is AppResult.Success -> if (!started.value) return staleInboxScope()
                    }
                    checkpoint = replacement
                    restarted = true
                    return@repeat
                }
                return incoming
            }
            val page = (incoming as AppResult.Success).value
            val hydrated = hydrate(access, page.changes)
            currentCoroutineContext().ensureActive()
            when (val current = currentAccess(workspaceId)) {
                is AppResult.Failed -> return current
                is AppResult.Success ->
                    if (!sameInboxScope(current.value, access)) return staleInboxScope()
            }
            val entries =
                when (hydrated) {
                    is AppResult.Failed -> return hydrated
                    is AppResult.Success -> hydrated.value
                }
            val complete =
                page.phase == InboxSyncPhase.CHANGES && !page.hasMore && !page.pendingPublication
            val next = advanceInboxCheckpoint(previous, page, clock.now())
            when (val committed = cache.commit(previous, next, entries)) {
                is AppResult.Failed -> return committed
                is AppResult.Success -> if (!committed.value) return staleInboxScope()
            }
            checkpoint = next
            if (complete) return AppResult.Success(InboxSyncOutcome(true, page.pollAfterSeconds))
        }
        return AppResult.Success(InboxSyncOutcome(false, 5))
    }

    private suspend fun reject(access: InboxAccess, failure: AppResult.Failed): AppResult.Failed {
        if (inboxFailureInvalidatesCache(failure.failure)) {
            withTimeoutOrNull(2.seconds) { cache.restrict(access, failure.failure) }
            withTimeoutOrNull(2.seconds) {
                if (failure.failure.code == "sync_scope_changed") {
                    val stored = cache.checkpoint(access)
                    currentCoroutineContext().ensureActive()
                    val previous = (stored as? AppResult.Success)?.value
                    if (previous != null) {
                        val replacement =
                            initialInboxCheckpoint(access, Uuid.random().toString(), clock.now())
                        cache.begin(replacement, previous.generation)
                    }
                } else if (inboxFailureRequiresCleanup(failure.failure)) cache.clear(access)
            }
        }
        return failure
    }

    private suspend fun nextPage(
        access: InboxAccess,
        checkpoint: InboxCheckpoint,
    ): AppResult<InboxSyncPage> =
        when (checkpoint.phase) {
            InboxSyncPhase.BOOTSTRAP ->
                when (
                    val result =
                        feed.bootstrap(
                            access.sessionId,
                            access.companyId,
                            setOf(SyncCollection.INBOX),
                            checkpoint.cursor,
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success -> {
                        val page = result.value
                        if (page.collections != setOf(SyncCollection.INBOX)) staleInboxScope()
                        else
                            AppResult.Success(
                                InboxSyncPage(
                                    page.items.map { SyncChange(it, SyncOperation.UPSERT) },
                                    if (page.nextCursor == null) InboxSyncPhase.CHANGES
                                    else InboxSyncPhase.BOOTSTRAP,
                                    requireNotNull(page.nextCursor ?: page.changesCursor),
                                    true,
                                    false,
                                    5,
                                )
                            )
                    }
                }
            InboxSyncPhase.CHANGES ->
                when (
                    val result =
                        feed.changes(
                            access.sessionId,
                            access.companyId,
                            setOf(SyncCollection.INBOX),
                            requireNotNull(checkpoint.cursor),
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(
                            InboxSyncPage(
                                result.value.items,
                                InboxSyncPhase.CHANGES,
                                result.value.cursor,
                                result.value.hasMore,
                                result.value.pendingPublication,
                                result.value.pollAfterSeconds,
                            )
                        )
                }
        }

    private suspend fun hydrate(
        access: InboxAccess,
        changes: List<SyncChange>,
    ): AppResult<List<InboxReplicaEntry>> {
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
        val entries = mutableListOf<InboxReplicaEntry>()
        for (batch in references.values.chunked(4)) {
            val results = coroutineScope {
                batch
                    .map { change ->
                        async {
                            val reference = change.reference
                            if (change.operation == SyncOperation.DELETE)
                                AppResult.Success(
                                    InboxReplicaEntry(reference.id, reference.version, null)
                                )
                            else
                                when (
                                    val result =
                                        remote.item(
                                            access.sessionId,
                                            access.companyId,
                                            reference.id,
                                        )
                                ) {
                                    is AppResult.Failed ->
                                        if (result.failure.code == "inbox_item_not_found")
                                            AppResult.Success(
                                                InboxReplicaEntry(
                                                    reference.id,
                                                    reference.version,
                                                    null,
                                                )
                                            )
                                        else result
                                    is AppResult.Success ->
                                        if (
                                            result.value.id != reference.id ||
                                                result.value.version < reference.version
                                        )
                                            AppResult.Failed(
                                                Failure(
                                                    FailureKind.Unexpected,
                                                    "The inbox response could not be read.",
                                                    code = "client.invalid_response",
                                                )
                                            )
                                        else
                                            AppResult.Success(
                                                InboxReplicaEntry(
                                                    reference.id,
                                                    result.value.version,
                                                    result.value,
                                                )
                                            )
                                }
                        }
                    }
                    .awaitAll()
            }
            results
                .filterIsInstance<AppResult.Failed>()
                .firstOrNull { inboxFailureInvalidatesCache(it.failure) }
                ?.let {
                    return it
                }
            for (result in results) when (result) {
                is AppResult.Failed -> return result
                is AppResult.Success -> entries.add(result.value)
            }
        }
        return AppResult.Success(entries.toList())
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<InboxAccess> {
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
        currentCoroutineContext().ensureActive()
        val latest =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return admitInbox(workspaceId, workspace, latest)
    }

    companion object {
        const val KEY = "hris-inbox-sync"
    }
}

private fun staleInboxScope() =
    AppResult.Failed(
        Failure(FailureKind.AccessDenied, "Refresh the inbox.", code = "client.inbox_scope_changed")
    )

private val cursorResetCodes =
    setOf(
        "sync_cursor_expired",
        "sync_cursor_out_of_range",
        "sync_cursor_invalidated",
        "sync_bootstrap_limit",
        "invalid_sync_cursor",
    )
