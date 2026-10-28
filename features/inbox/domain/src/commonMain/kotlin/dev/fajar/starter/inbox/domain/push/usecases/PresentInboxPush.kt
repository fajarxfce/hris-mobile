package dev.fajar.starter.inbox.domain.push.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.push.repositories.NativePushStateRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.*
import dev.fajar.starter.inbox.domain.push.entities.*
import dev.fajar.starter.inbox.domain.push.repositories.InboxPushQueueRepository
import dev.fajar.starter.inbox.domain.repositories.*
import dev.fajar.starter.notifications.domain.entities.*
import dev.fajar.starter.notifications.domain.repositories.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Call after authorized Inbox synchronization. No provider title, body or URL is displayed. */
class PresentInboxPush(
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val queue: InboxPushQueueRepository,
    private val remote: InboxRepository,
    private val cache: InboxCacheRepository,
    private val push: NativePushStateRepository,
    private val permissions: NotificationAccessRepository,
    private val delivery: NotificationDeliveryRepository,
    private val clock: Clock,
    private val mutations: Mutex,
) {
    suspend operator fun invoke(work: PreparedInboxPush, copy: InboxPushCopy): AppResult<Unit> =
        withTimeoutOrNull(20.seconds) { present(work, copy) }
            ?: inboxCommandFailure("client.inbox_push_timeout", FailureKind.Timeout)

    private suspend fun present(work: PreparedInboxPush, copy: InboxPushCopy): AppResult<Unit> {
        if (
            copy.title.length !in 1..120 ||
                copy.body.length > 300 ||
                work.hint.preparations !in 1..3
        )
            return inboxCommandFailure("client.inbox_push_invalid", FailureKind.Validation)
        val admission = guard(work)
        if (admission is AppResult.Failed) return admission
        val canonical =
            when (val result = canonical(work)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val enabled =
            when (val result = enabled(work)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val claimed = mutations.withLock { claim(work) }
        if (claimed is AppResult.Failed) return claimed
        if (!(claimed as AppResult.Success).value || !enabled || canonical.readAt != null)
            return AppResult.Success(Unit)
        val beforeDisplay = guard(work)
        if (beforeDisplay is AppResult.Failed) return beforeDisplay
        when (val stillEnabled = enabled(work)) {
            is AppResult.Failed -> return stillEnabled
            is AppResult.Success -> if (!stillEnabled.value) return AppResult.Success(Unit)
        }
        val destination =
            "accounts/${work.access.accountId}/companies/${work.access.companyId}/inbox/${canonical.id}"
        val result =
            delivery.show(
                NotificationMessage(
                    work.hint.eventId,
                    copy.title,
                    copy.body,
                    destination,
                    clock.now().toEpochMilliseconds(),
                )
            )
        currentCoroutineContext().ensureActive()
        return result
    }

    private suspend fun canonical(work: PreparedInboxPush): AppResult<InboxItem> {
        val item = remote.item(work.access.sessionId, work.access.companyId, work.hint.inboxId)
        currentCoroutineContext().ensureActive()
        val owner = validateInboxCommandAccess(currentAccess(work.workspaceId), work.access)
        if (owner is AppResult.Failed) return owner
        if (item is AppResult.Failed) {
            if (inboxFailureInvalidatesCache(item.failure)) {
                withTimeoutOrNull(2.seconds) { cache.restrict(work.access, item.failure) }
                currentCoroutineContext().ensureActive()
            }
            return item
        }
        val current = guard(work)
        if (current is AppResult.Failed) return current
        val canonical = (item as AppResult.Success).value
        if (canonical.id != work.hint.inboxId || work.hint.companyId != work.access.companyId)
            return inboxCommandFailure("client.inbox_push_invalid", FailureKind.Unexpected)
        if (canonical.readAt == null) {
            val local = cache.observe(work.access, InboxCacheQuery.Item(canonical.id)).first()
            val localOwner = guard(work)
            if (localOwner is AppResult.Failed) return localOwner
            when (local) {
                is AppResult.Failed -> return local
                is AppResult.Success -> {
                    val owned = validateStoredInbox(work.access, local.value.checkpoint)
                    if (owned is AppResult.Failed) return owned
                    val entry = local.value.items.singleOrNull { it.id == canonical.id }
                    if (
                        local.value.checkpoint == null ||
                            entry == null ||
                            entry.version < canonical.version
                    )
                        return inboxCommandFailure("client.inbox_updates_pending")
                }
            }
        }
        return AppResult.Success(canonical)
    }

    private suspend fun enabled(work: PreparedInboxPush): AppResult<Boolean> {
        val state =
            when (val result = push.read()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val access =
            when (val result = permissions.check()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val current = guard(work)
        if (current is AppResult.Failed) return current
        when (val latest = push.read()) {
            is AppResult.Failed -> return latest
            is AppResult.Success -> if (latest.value != state) return AppResult.Success(false)
        }
        val afterRead = guard(work)
        if (afterRead is AppResult.Failed) return afterRead
        val owner = state.owner
        return AppResult.Success(
            state.desired &&
                !state.retiringToken &&
                owner != null &&
                owner.localSessionId == work.access.sessionId &&
                owner.accountId == work.access.accountId &&
                owner.audience == work.access.audience &&
                access == NotificationAccess.Granted
        )
    }

    private suspend fun claim(work: PreparedInboxPush): AppResult<Boolean> {
        val current = guard(work)
        if (current is AppResult.Failed) return current
        val state =
            when (val result = queue.read()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val owner = InboxPushOwner(work.access.audience, work.access.accountId)
        if (state.owner != owner || work.hint !in state.pending) return AppResult.Success(false)
        if (state.attempts.size >= 512) return inboxCommandFailure("client.inbox_push_capacity")
        val afterRead = guard(work)
        if (afterRead is AppResult.Failed) return afterRead
        // The OS operation may complete after cancellation; persist the attempt before invoking it.
        return queue.compareAndSet(
            state,
            state.copy(
                pending = state.pending.filterNot { it == work.hint },
                attempts =
                    state.attempts +
                        InboxPushAttempt(work.hint.eventId, work.hint.companyId, owner, clock.now()),
            ),
        )
    }

    private suspend fun guard(work: PreparedInboxPush): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val now = clock.now()
        if (
            now >= work.preparedAt + 30.seconds ||
                now >= work.access.sessionExpiresAt ||
                now >= work.access.workspaceExpiresAt
        )
            return inboxCommandFailure("client.inbox_push_expired", FailureKind.Unavailable)
        val current = validateInboxCommandAccess(currentAccess(work.workspaceId), work.access)
        if (current is AppResult.Failed) return current
        val admission = cache.admission(work.access)
        currentCoroutineContext().ensureActive()
        val owner = validateInboxCommandAccess(currentAccess(work.workspaceId), work.access)
        if (owner is AppResult.Failed) return owner
        return when (admission) {
            is AppResult.Failed -> admission
            is AppResult.Success ->
                admission.value.denial?.let { AppResult.Failed(it) }
                    ?: if (
                        clock.now() >=
                            minOf(
                                work.preparedAt + 30.seconds,
                                work.access.sessionExpiresAt,
                                work.access.workspaceExpiresAt,
                            )
                    )
                        inboxCommandFailure("client.inbox_push_expired", FailureKind.Unavailable)
                    else owner
        }
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<InboxAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return inboxCommandFailure(
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
        val current =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return admitInbox(workspaceId, workspace, current)
    }
}
