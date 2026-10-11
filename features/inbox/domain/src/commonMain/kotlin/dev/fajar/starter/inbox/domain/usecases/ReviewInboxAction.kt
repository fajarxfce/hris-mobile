package dev.fajar.starter.inbox.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.*
import dev.fajar.starter.inbox.domain.repositories.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

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
        val canonical = remote.item(access.sessionId, access.companyId, record.command.itemId)
        currentCoroutineContext().ensureActive()
        when (val current = validateInboxCommandAccess(currentAccess(workspaceId), access)) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
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
                val valid = validateInboxCache(access, page.checkpoint, clock.now())
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
        when (val current = validateInboxCommandAccess(currentAccess(workspaceId), access)) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        return commands.remove(access, record)
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<InboxAccess> {
        val workspace =
            when (val result = workspaces.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        return admitInbox(workspaceId, workspace, session, clock.now())
    }
}
