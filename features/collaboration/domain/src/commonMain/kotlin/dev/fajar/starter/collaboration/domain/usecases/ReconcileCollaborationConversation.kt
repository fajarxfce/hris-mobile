package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSetupFailure
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Retires a confirmed operation only after its owned conversation/setup version is observed. */
class ReconcileCollaborationConversation(
    private val commands: CollaborationConversationCommandRepository,
    private val conversations: CollaborationConversationRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(workspaceId: String, commandId: String): AppResult<Boolean> {
        val captured = capture(workspaceId)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        val stored = commands.get(owner, commandId)
        val admitted = continuation(workspaceId, owner, stored)
        if (admitted is AppResult.Failed) return admitted
        if (stored is AppResult.Failed) return stored
        val record = (stored as AppResult.Success).value ?: return AppResult.Success(false)
        if (record.command.id != commandId || record.command.binding != owner.setupBinding())
            return collaborationSetupFailure("client.collaboration_command_changed")
        val outcome =
            record.outcome as? CollaborationConversationOutcome.Confirmed
                ?: return AppResult.Success(false)
        val observed: AppResult<Boolean> =
            when (val action = record.command.action) {
                is CollaborationConversationAction.Open ->
                    when (val result = conversations.get(owner, outcome.receipt.id)) {
                        is AppResult.Failed -> result
                        is AppResult.Success ->
                            AppResult.Success(
                                result.value.let {
                                    it.id == outcome.receipt.id &&
                                        it.companyId == owner.companyId &&
                                        setOf(it.firstAccountId, it.secondAccountId) ==
                                            setOf(owner.accountId, action.peerAccountId) &&
                                        it.version == outcome.receipt.version
                                }
                            )
                    }
                is CollaborationConversationAction.RequestRoom ->
                    when (val result = conversations.room(owner, action.conversationId)) {
                        is AppResult.Failed -> result
                        is AppResult.Success ->
                            AppResult.Success(
                                result.value.let {
                                    it.conversationId == outcome.receipt.id &&
                                        it.version != null &&
                                        it.version >= outcome.receipt.version
                                }
                            )
                    }
            }
        val current = continuation(workspaceId, owner, observed)
        if (current is AppResult.Failed) return current
        if (observed is AppResult.Failed) return observed
        if (!(observed as AppResult.Success).value) return AppResult.Success(false)
        val removed = commands.remove(owner, record)
        val latest = continuation(workspaceId, owner, removed)
        return if (latest is AppResult.Failed) latest else removed
    }

    private suspend fun capture(workspaceId: String) =
        sessions.captureCollaborationOwner(workspaceId, workspaces, server, build, clock)

    private suspend fun continuation(
        workspaceId: String,
        owner: CollaborationOwner,
        received: AppResult<*>?,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        return requireCollaborationOwner(capture(workspaceId), owner, received)
    }
}
