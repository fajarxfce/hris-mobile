@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.inbox.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.*
import dev.fajar.starter.inbox.domain.repositories.*
import dev.fajar.starter.workspace.domain.policies.workspaceFailure
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

class PrepareInboxAction(
    private val commands: InboxCommandRepository,
    private val cache: InboxCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        itemId: String,
        version: Long,
        action: InboxAction,
    ): AppResult<InboxCommandRecord> {
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val admitted = readContinuation(workspaceId, access)
        if (admitted is AppResult.Failed) return admitted
        val page =
            when (val result = cache.observe(access, InboxCacheQuery.Item(itemId)).first()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val readOwner = readContinuation(workspaceId, access)
        if (readOwner is AppResult.Failed) return readOwner
        val admission = validateInboxActionSnapshot(access, page.checkpoint, clock.now())
        if (admission is AppResult.Failed) return admission
        val item =
            page.items.singleOrNull() ?: return inboxCommandFailure("client.inbox_item_unavailable")
        if (page.checkpoint == null || item.version != version)
            return inboxCommandFailure("client.inbox_review_required")
        if (
            inboxActionApplied(action, item) ||
                (action == InboxAction.ACKNOWLEDGE && !item.acknowledgementRequired)
        )
            return inboxCommandFailure("client.inbox_action_unavailable")
        val existing =
            when (val result = commands.observe(access, itemId).first()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        when (val current = readContinuation(workspaceId, access)) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        if (existing != null)
            return if (existing.command.action == action) AppResult.Success(existing)
            else inboxCommandFailure("client.inbox_action_pending")
        currentCoroutineContext().ensureActive()
        val command =
            InboxCommand(
                Uuid.random().toString(),
                access.accountId,
                access.audience,
                access.companyId,
                itemId,
                version,
                action,
                clock.now(),
            )
        when (val result = commands.insert(access, command)) {
            is AppResult.Failed -> return result
            is AppResult.Success ->
                when (result.value) {
                    InboxCommandAdmission.CAPACITY_EXCEEDED ->
                        return inboxCommandFailure("client.command_queue_full", FailureKind.Storage)
                    InboxCommandAdmission.SCOPE_CHANGED ->
                        return inboxCommandFailure(
                            "client.inbox_scope_changed",
                            FailureKind.AccessDenied,
                        )
                    InboxCommandAdmission.STORED,
                    InboxCommandAdmission.OCCUPIED -> Unit
                }
        }
        currentCoroutineContext().ensureActive()
        when (val current = readContinuation(workspaceId, access)) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        val retained =
            when (val result = commands.observe(access, itemId).first()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        when (val current = readContinuation(workspaceId, access)) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        return if (retained != null && retained.command.action == action)
            AppResult.Success(retained)
        else inboxCommandFailure("client.inbox_action_pending")
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
