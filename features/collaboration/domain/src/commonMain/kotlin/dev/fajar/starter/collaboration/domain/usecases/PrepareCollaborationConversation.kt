@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Explicit live-admitted control preparation. Offline message composition is separate. */
class PrepareCollaborationConversation(
    private val commands: CollaborationConversationCommandRepository,
    private val conversations: CollaborationConversationRepository,
    private val setup: CollaborationSetupRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val lists: CollaborationListRepository,
) {
    suspend operator fun invoke(
        workspaceId: String,
        action: CollaborationConversationAction,
    ): AppResult<CollaborationConversationCommandRecord> =
        invoke(CollaborationOperationScope.Selected(workspaceId), action)

    suspend operator fun invoke(
        scope: CollaborationOperationScope,
        action: CollaborationConversationAction,
    ): AppResult<CollaborationConversationCommandRecord> {
        val captured = capture(scope)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        val valid = validAction(scope, action, owner)
        if (!valid)
            return collaborationSetupFailure(
                "client.collaboration_invalid_command",
                FailureKind.Validation,
            )
        continuation(scope, owner, null).let { if (it is AppResult.Failed) return it }
        val stored = commands.current(owner, action.resourceId())
        val admitted = continuation(scope, owner, stored)
        if (admitted is AppResult.Failed) return admitted
        if (stored is AppResult.Failed) return stored
        (stored as AppResult.Success).value?.let {
            return retained(it, action, owner)
        }
        if (action is CollaborationConversationAction.RequestRoom) {
            val device = setup.read(owner)
            val current = continuation(scope, owner, device, device)
            if (current is AppResult.Failed) return current
            if (device is AppResult.Failed) return device
            val value = (device as AppResult.Success).value
            if (!matchesCollaborationRoomRequestDevice(action, value, build))
                return collaborationSetupFailure("client.collaboration_setup_changed")
            val room = conversations.room(owner, action.conversationId)
            val latest = continuation(scope, owner, room, room)
            if (latest is AppResult.Failed) return latest
            if (room is AppResult.Failed) return room
            val status = (room as AppResult.Success).value
            val validStatus =
                validateCollaborationRoomRequestStatus(
                    action,
                    owner,
                    status,
                    clock.now().toEpochMilliseconds(),
                )
            if (validStatus is AppResult.Failed) return validStatus
        }
        val command =
            CollaborationConversationCommand(
                Uuid.random().toString(),
                owner.setupBinding(),
                clock.now(),
                action,
            )
        currentCoroutineContext().ensureActive()
        val inserted = commands.insert(owner, command)
        val current = continuation(scope, owner, inserted)
        if (current is AppResult.Failed) return current
        if (inserted is AppResult.Failed) return inserted
        when ((inserted as AppResult.Success).value) {
            CollaborationSetupInsertion.STORED,
            CollaborationSetupInsertion.OCCUPIED -> Unit
            CollaborationSetupInsertion.CAPACITY_EXCEEDED ->
                return collaborationSetupFailure("client.command_queue_full", FailureKind.Storage)
            CollaborationSetupInsertion.SCOPE_CHANGED ->
                return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        }
        val saved = commands.current(owner, action.resourceId())
        val latest = continuation(scope, owner, saved)
        if (latest is AppResult.Failed) return latest
        if (saved is AppResult.Failed) return saved
        return (saved as AppResult.Success).value?.let { retained(it, action, owner) }
            ?: collaborationSetupFailure("client.collaboration_command_changed")
    }

    private fun retained(
        record: CollaborationConversationCommandRecord,
        action: CollaborationConversationAction,
        owner: CollaborationOwner,
    ): AppResult<CollaborationConversationCommandRecord> =
        if (record.command.binding == owner.setupBinding() && record.command.action == action)
            AppResult.Success(record)
        else collaborationSetupFailure("client.collaboration_conversation_pending")

    private fun validAction(
        scope: CollaborationOperationScope,
        action: CollaborationConversationAction,
        owner: CollaborationOwner,
    ) =
        collaborationControlDeviceMatches(scope, action) &&
            when (action) {
                is CollaborationConversationAction.Open ->
                    canonicalId(action.peerAccountId) && action.peerAccountId != owner.accountId
                is CollaborationConversationAction.RequestRoom ->
                    canonicalId(action.conversationId) &&
                        canonicalId(action.deviceId) &&
                        action.deviceVersion in 0..999_999 &&
                        action.provisioningVersion?.let { it in 0..999_998 } != false
            }

    private fun canonicalId(value: String) =
        value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))

    private suspend fun capture(scope: CollaborationOperationScope) =
        sessions.captureCollaborationOwner(scope, workspaces, server, build, clock)

    private suspend fun continuation(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        received: AppResult<*>?,
        response: AppResult<*>? = null,
    ): AppResult<Unit> =
        sessions.continueCollaborationControl(
            scope,
            owner,
            received,
            response,
            lists,
            workspaces,
            server,
            build,
            clock,
        )
}
