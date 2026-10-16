package dev.fajar.starter.inbox.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.*
import dev.fajar.starter.inbox.domain.repositories.*
import dev.fajar.starter.workspace.domain.policies.workspaceFailure
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Retires completed evidence, or a definite rejection after an explicit fresh review. */
class ReviewInboxAction(
    private val commands: InboxCommandRepository,
    private val remote: InboxRepository,
    private val cache: InboxCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(workspaceId: String, commandId: String): AppResult<Boolean> {
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val admitted = readContinuation(workspaceId, access)
        if (admitted is AppResult.Failed) return admitted
        val record =
            when (val result = commands.find(access, commandId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value ?: return AppResult.Success(false)
            }
        if (
            record.outcome !is InboxCommandOutcome.Confirmed &&
                record.outcome !is InboxCommandOutcome.Rejected
        )
            return inboxCommandFailure("client.inbox_action_unconfirmed")
        val beforeRead = readContinuation(workspaceId, access)
        if (beforeRead is AppResult.Failed) return beforeRead
        val canonical = remote.item(access.sessionId, access.companyId, record.command.itemId)
        currentCoroutineContext().ensureActive()
        when (val current = validateInboxCommandAccess(currentAccess(workspaceId), access)) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        if (canonical is AppResult.Failed && inboxFailureInvalidatesCache(canonical.failure)) {
            withTimeoutOrNull(2.seconds) { cache.restrict(access, canonical.failure) }
            currentCoroutineContext().ensureActive()
            val owner = validateInboxCommandAccess(currentAccess(workspaceId), access)
            return if (owner is AppResult.Failed) owner else canonical
        }
        val readOwner = readContinuation(workspaceId, access)
        if (readOwner is AppResult.Failed) return readOwner
        when (canonical) {
            is AppResult.Failed ->
                if (canonical.failure.code != "inbox_item_not_found") return canonical
            is AppResult.Success -> {
                val page =
                    when (
                        val result =
                            cache
                                .observe(access, InboxCacheQuery.Item(record.command.itemId))
                                .first()
                    ) {
                        is AppResult.Failed -> return result
                        is AppResult.Success -> result.value
                    }
                val valid = validateInboxActionSnapshot(access, page.checkpoint, clock.now())
                if (valid is AppResult.Failed) return valid
                val item = page.items.singleOrNull()
                if (
                    page.checkpoint == null ||
                        item == null ||
                        item.version < canonical.value.version
                )
                    return inboxCommandFailure("client.inbox_updates_pending")
                if (
                    record.outcome is InboxCommandOutcome.Confirmed &&
                        (canonical.value.version < record.outcome.version ||
                            !inboxActionApplied(record.command.action, canonical.value) ||
                            !inboxActionApplied(record.command.action, item))
                )
                    return inboxCommandFailure("client.inbox_updates_pending")
            }
        }
        currentCoroutineContext().ensureActive()
        when (val current = readContinuation(workspaceId, access)) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        val removed = commands.remove(access, record)
        currentCoroutineContext().ensureActive()
        val owner = readContinuation(workspaceId, access)
        return if (owner is AppResult.Failed) owner else removed
    }

    private suspend fun readContinuation(
        workspaceId: String,
        access: InboxAccess,
    ): AppResult<Unit> {
        val current = validateInboxCommandAccess(currentAccess(workspaceId), access)
        if (current is AppResult.Failed) return current
        val admission = cache.admission(access)
        currentCoroutineContext().ensureActive()
        val owner = validateInboxCommandAccess(currentAccess(workspaceId), access)
        if (owner is AppResult.Failed) return owner
        return when (admission) {
            is AppResult.Failed -> admission
            is AppResult.Success -> admission.value.denial?.let { AppResult.Failed(it) } ?: owner
        }
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
}
