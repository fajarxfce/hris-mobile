package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reads retained control intent locally; age and a new build never rewrite its identity. */
class ReadCollaborationControl(
    private val setupCommands: CollaborationSetupCommandRepository,
    private val conversationCommands: CollaborationConversationCommandRepository,
    private val admission: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend fun setup(workspaceId: String) = setup(CollaborationLocalScope.Selected(workspaceId))

    suspend fun setup(
        scope: CollaborationLocalScope
    ): AppResult<CollaborationRead<CollaborationSetupCommandRecord?>> =
        read(scope, { setupCommands.current(it) }) { record, owner ->
            record == null ||
                (record.command.binding == owner.setupBinding() &&
                    ((record.outcome as? CollaborationSetupOutcome.Bootstrapped)?.let {
                        record.attempts > 0 &&
                            collaborationGatewayMatchesCommand(owner, record.command, it.gateway)
                    } != false))
        }

    suspend fun direct(workspaceId: String, peerAccountId: String) =
        direct(CollaborationLocalScope.Selected(workspaceId), peerAccountId)

    suspend fun direct(scope: CollaborationLocalScope, peerAccountId: String) =
        conversation(scope, "direct", peerAccountId)

    suspend fun room(workspaceId: String, conversationId: String) =
        room(CollaborationLocalScope.Selected(workspaceId), conversationId)

    suspend fun room(scope: CollaborationLocalScope, conversationId: String) =
        conversation(scope, "room", conversationId)

    private suspend fun conversation(
        scope: CollaborationLocalScope,
        kind: String,
        id: String,
    ): AppResult<CollaborationRead<CollaborationConversationCommandRecord?>> {
        if (!canonicalId.matches(id))
            return collaborationSetupFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        val resource = "$kind/$id"
        return read(scope, { conversationCommands.current(it, resource) }) { record, owner ->
            record == null ||
                (record.command.binding == owner.setupBinding() &&
                    record.command.action.resourceId() == resource)
        }
    }

    private suspend fun <T> read(
        scope: CollaborationLocalScope,
        acquire: suspend (CollaborationOwner) -> AppResult<T>,
        valid: (T, CollaborationOwner) -> Boolean,
    ): AppResult<CollaborationRead<T>> {
        val captured = capture(scope)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        val before = continuation(scope, owner, null)
        if (before is AppResult.Failed) return before
        val received = acquire(owner)
        val current = continuation(scope, owner, received)
        if (current is AppResult.Failed) return current
        if (received is AppResult.Failed) return received
        val value = (received as AppResult.Success).value
        if (!valid(value, owner))
            return collaborationSetupFailure("client.collaboration_command_changed")
        return AppResult.Success(CollaborationRead(owner, value))
    }

    private suspend fun continuation(
        scope: CollaborationLocalScope,
        owner: CollaborationOwner,
        received: AppResult<*>?,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val current = requireCollaborationOwner(capture(scope), owner, received)
        if (current is AppResult.Failed) return current
        val gate = admission.admission(owner.localListAccess())
        currentCoroutineContext().ensureActive()
        val checked = requireCollaborationOwner(capture(scope), owner, gate)
        if (checked is AppResult.Failed) return checked
        return when (gate) {
            is AppResult.Failed -> gate
            is AppResult.Success ->
                gate.value.denial?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
        }
    }

    private suspend fun capture(scope: CollaborationLocalScope) =
        sessions.captureCollaborationLocalOwner(scope, workspaces, server, build, clock)

    private companion object {
        val canonicalId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
