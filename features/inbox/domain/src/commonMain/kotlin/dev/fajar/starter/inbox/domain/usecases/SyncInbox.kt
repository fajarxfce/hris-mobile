@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.inbox.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.*
import dev.fajar.starter.inbox.domain.repositories.*
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
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
        var checkpoint =
            when (val result = cache.checkpoint(access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (
            checkpoint != null &&
                (!sameInboxScope(checkpoint.access, access) || checkpoint.expiresAt <= clock.now())
        ) {
            val cleared = cache.clear(access)
            if (cleared is AppResult.Failed) return cleared
            checkpoint = null
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
            val incoming = nextPage(access, previous)
            currentCoroutineContext().ensureActive()
            when (val current = currentAccess(workspaceId)) {
                is AppResult.Failed -> return current
                is AppResult.Success ->
                    if (!sameInboxScope(current.value, access)) return staleInboxScope()
            }
            if (incoming is AppResult.Failed) {
                if (incoming.failure.code in cursorResetCodes && !restarted) {
                    val cleared = cache.clear(access)
                    if (cleared is AppResult.Failed) return cleared
                    restarted = true
                    checkpoint = null
                    return@repeat
                }
                if (incoming.failure.code in accessLossCodes) {
                    val cleared = cache.clear(access)
                    if (cleared is AppResult.Failed) return cleared
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
                    is AppResult.Failed -> {
                        if (hydrated.failure.code in accessLossCodes) {
                            val cleared = cache.clear(access)
                            if (cleared is AppResult.Failed) return cleared
                        }
                        return hydrated
                    }
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
        return admitInbox(workspaceId, workspace, session, clock.now())
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
        "sync_scope_changed",
        "sync_cursor_expired",
        "sync_cursor_out_of_range",
        "sync_cursor_invalidated",
        "sync_bootstrap_limit",
        "invalid_sync_cursor",
    )
private val accessLossCodes =
    setOf("session_revoked", "company_access_denied", "sync_access_denied")
