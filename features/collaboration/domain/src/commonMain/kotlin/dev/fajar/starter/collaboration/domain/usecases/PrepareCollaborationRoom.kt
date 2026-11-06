@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Finite room readiness acquisition. The worker owns provisioning and message publication. */
class PrepareCollaborationRoom(
    private val commands: CollaborationConversationCommandRepository,
    private val conversations: CollaborationConversationRepository,
    private val messages: CollaborationMessageRepository,
    private val setup: CollaborationSetupRepository,
    private val schedules: SyncScheduleRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        expected: CollaborationLocalAccess,
        conversationId: String,
        deviceId: String,
        deviceVersion: Long,
        retryRequested: Boolean,
    ): AppResult<CollaborationRoomReadiness> {
        if (
            !validCollaborationMessageId(conversationId) ||
                !validCollaborationMessageId(deviceId) ||
                deviceVersion !in 0..999_999
        )
            return collaborationSetupFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        val scope = CollaborationOperationScope.Selected(workspaceId)
        val captured = sessions.captureCollaborationOwner(scope, workspaces, server, build, clock)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        if (owner.localListAccess() != expected)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        continuation(scope, owner).let { if (it is AppResult.Failed) return it }
        val retained = commands.current(owner, "room/$conversationId")
        continuation(scope, owner, retained).let { if (it is AppResult.Failed) return it }
        if (retained is AppResult.Failed) return retained
        val command = (retained as AppResult.Success).value
        if (
            command != null &&
                (command.command.binding != owner.setupBinding() ||
                    command.command.action.resourceId() != "room/$conversationId")
        )
            return collaborationSetupFailure("client.collaboration_command_changed")
        val status = readRoom(scope, owner, conversationId)
        if (status is AppResult.Failed) return status
        val room = (status as AppResult.Success).value
        if (command == null && room.state == CollaborationDirectRoomState.READY)
            return AppResult.Success(CollaborationRoomReadiness.READY)
        val loaded = messages.observe(expected, conversationId).first()
        continuation(scope, owner, loaded).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        val snapshot = (loaded as AppResult.Success).value
        if (snapshot.size > 128)
            return collaborationSetupFailure("client.collaboration_message_changed")
        val records = snapshot.toList()
        if (
            records.map { it.intent.id }.distinct().size != records.size ||
                records.any {
                    it.intent.owner != expected.messageOwner() ||
                        it.intent.conversationId != conversationId
                }
        )
            return collaborationSetupFailure("client.collaboration_message_changed")
        val pending =
            records.any {
                it.state != CollaborationMessageState.Cancelled &&
                    it.state !is CollaborationMessageState.Sent
            }
        if (
            command == null &&
                pending &&
                retryRequested &&
                room.retryable &&
                room.state in
                    setOf(
                        CollaborationDirectRoomState.FAILED,
                        CollaborationDirectRoomState.CANCELLED,
                    )
        ) {
            val action =
                CollaborationConversationAction.RequestRoom(
                    conversationId,
                    deviceId,
                    deviceVersion,
                    room.version,
                )
            // A retry always identifies the observed failed generation; never downgrade to an
            // initial request.
            if (action.provisioningVersion == null || action.provisioningVersion !in 0..999_998)
                return collaborationSetupFailure("client.collaboration_room_changed")
            val prepared = stageRetry(scope, owner, action)
            if (prepared is AppResult.Failed) return prepared
        }
        if (pending || command != null) {
            val requested =
                withTimeoutOrNull(1_500) { schedules.request(QueueCollaborationText.DELIVERY_KEY) }
                    ?: collaborationSetupFailure(
                        "client.collaboration_schedule_timeout",
                        FailureKind.Timeout,
                    )
            continuation(scope, owner, requested).let { if (it is AppResult.Failed) return it }
            if (requested is AppResult.Failed) return requested
        }
        if (!pending)
            return AppResult.Success(
                if (room.state == CollaborationDirectRoomState.READY)
                    CollaborationRoomReadiness.READY
                else CollaborationRoomReadiness.WAITING
            )
        return awaitReadiness(scope, owner, conversationId)
    }

    /** Polls only the existing request; each delay and response rechecks the captured owner. */
    private suspend fun awaitReadiness(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        conversationId: String,
    ): AppResult<CollaborationRoomReadiness> {
        repeat(12) { attempt ->
            if (attempt > 0) delay(2_000)
            continuation(scope, owner).let { if (it is AppResult.Failed) return it }
            val observed = readRoom(scope, owner, conversationId)
            if (observed is AppResult.Failed) return observed
            val state = (observed as AppResult.Success).value.state
            if (state == CollaborationDirectRoomState.READY)
                return AppResult.Success(CollaborationRoomReadiness.READY)
            if (
                state !in
                    setOf(
                        CollaborationDirectRoomState.NOT_REQUESTED,
                        CollaborationDirectRoomState.QUEUED,
                        CollaborationDirectRoomState.RUNNING,
                    )
            )
                return AppResult.Success(CollaborationRoomReadiness.WAITING)
        }
        return AppResult.Success(CollaborationRoomReadiness.WAITING)
    }

    private suspend fun stageRetry(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        action: CollaborationConversationAction.RequestRoom,
    ): AppResult<Unit> {
        val device = setup.read(owner)
        continuation(scope, owner, device, device).let { if (it is AppResult.Failed) return it }
        if (device is AppResult.Failed) return device
        if (
            !matchesCollaborationRoomRequestDevice(
                action,
                (device as AppResult.Success).value,
                build,
            )
        )
            return collaborationSetupFailure("client.collaboration_setup_changed")
        val loaded = readRoom(scope, owner, action.conversationId)
        if (loaded is AppResult.Failed) return loaded
        val valid =
            validateCollaborationRoomRequestStatus(
                action,
                owner,
                (loaded as AppResult.Success).value,
                clock.now().toEpochMilliseconds(),
            )
        if (valid is AppResult.Failed) return valid
        val intent =
            CollaborationConversationCommand(
                Uuid.random().toString(),
                owner.setupBinding(),
                clock.now(),
                action,
            )
        currentCoroutineContext().ensureActive()
        val inserted = commands.insert(owner, intent)
        continuation(scope, owner, inserted).let { if (it is AppResult.Failed) return it }
        if (inserted is AppResult.Failed) return inserted
        when ((inserted as AppResult.Success).value) {
            CollaborationSetupInsertion.CAPACITY_EXCEEDED ->
                return collaborationSetupFailure("client.command_queue_full", FailureKind.Storage)
            CollaborationSetupInsertion.SCOPE_CHANGED ->
                return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
            CollaborationSetupInsertion.STORED,
            CollaborationSetupInsertion.OCCUPIED -> Unit
        }
        val stored = commands.current(owner, action.resourceId())
        continuation(scope, owner, stored).let { if (it is AppResult.Failed) return it }
        if (stored is AppResult.Failed) return stored
        val retained = (stored as AppResult.Success).value?.command
        return if (retained?.binding == owner.setupBinding() && retained.action == action)
            AppResult.Success(Unit)
        else collaborationSetupFailure("client.collaboration_conversation_pending")
    }

    private suspend fun readRoom(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        conversationId: String,
    ): AppResult<CollaborationDirectRoom> {
        val result = conversations.room(owner, conversationId)
        continuation(scope, owner, result, result).let { if (it is AppResult.Failed) return it }
        if (result is AppResult.Failed) return result
        if ((result as AppResult.Success).value.conversationId != conversationId)
            return collaborationSetupFailure("client.collaboration_command_changed")
        return result
    }

    private suspend fun continuation(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        result: AppResult<*>? = null,
        response: AppResult<*>? = null,
    ): AppResult<Unit> =
        sessions.continueCollaborationControl(
            scope,
            owner,
            result,
            response,
            lists,
            workspaces,
            server,
            build,
            clock,
        )
}
