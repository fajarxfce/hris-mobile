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
import kotlinx.coroutines.*

/** Explicit live entry. Offline history observation has a separate owned-facts contract. */
class EnterCollaborationConversation(
    private val runtimes: CollaborationRuntimeRepository,
    private val conversations: CollaborationConversationRepository,
    private val setup: CollaborationSetupRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        deviceId: String,
        conversationId: String,
    ): AppResult<CollaborationTimelineLease> {
        if (!canonicalId.matches(deviceId) || !canonicalId.matches(conversationId))
            return collaborationSetupFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        val owner =
            when (val captured = capture(workspaceId)) {
                is AppResult.Failed -> return captured
                is AppResult.Success -> captured.value
            }
        val registered = setup.read(owner)
        continuation(workspaceId, owner, registered).let { if (it is AppResult.Failed) return it }
        val device =
            when (registered) {
                is AppResult.Failed -> return registered
                is AppResult.Success -> registered.value
            }
        if (
            device == null ||
                device.id != deviceId ||
                device.state != CollaborationDeviceState.ACTIVE ||
                device.platform.name != build.platform.name.uppercase()
        )
            return collaborationSetupFailure(
                "client.collaboration_device_unavailable",
                FailureKind.Unavailable,
            )
        val direct = conversations.get(owner, conversationId)
        continuation(workspaceId, owner, direct).let { if (it is AppResult.Failed) return it }
        val conversation =
            when (direct) {
                is AppResult.Failed -> return direct
                is AppResult.Success -> direct.value
            }
        if (
            conversation.id != conversationId ||
                conversation.companyId != owner.companyId ||
                owner.accountId !in
                    listOf(conversation.firstAccountId, conversation.secondAccountId)
        )
            return collaborationSetupFailure(
                "client.collaboration_conversation_mismatch",
                FailureKind.AccessDenied,
            )
        val provisioned = conversations.room(owner, conversationId)
        continuation(workspaceId, owner, provisioned).let { if (it is AppResult.Failed) return it }
        val room =
            when (provisioned) {
                is AppResult.Failed -> return provisioned
                is AppResult.Success -> provisioned.value
            }
        if (room.conversationId != conversationId)
            return collaborationSetupFailure(
                "client.collaboration_conversation_mismatch",
                FailureKind.AccessDenied,
            )
        val matrixRoomId = room.matrixRoomId
        if (
            room.state != CollaborationDirectRoomState.READY ||
                matrixRoomId == null ||
                !matrixRoomId.startsWith('!') ||
                matrixRoomId.length !in 2..255 ||
                matrixRoomId.any { it.isWhitespace() || it.isISOControl() }
        )
            return collaborationSetupFailure(
                "client.collaboration_room_not_ready",
                FailureKind.Unavailable,
            )
        var acquired: CollaborationTimelineLease? = null
        var handedOver = false
        try {
            val entered = runtimes.enterTimeline(owner, deviceId, matrixRoomId)
            if (entered is AppResult.Success) acquired = entered.value
            continuation(workspaceId, owner, entered).let { if (it is AppResult.Failed) return it }
            if (entered is AppResult.Failed) return entered
            val timeline = requireNotNull(acquired)
            if (
                timeline.owner != owner ||
                    timeline.deviceId != deviceId ||
                    timeline.matrixRoomId != matrixRoomId
            )
                return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
            currentCoroutineContext().ensureActive()
            handedOver = true
            return AppResult.Success(timeline)
        } finally {
            if (!handedOver) withContext(NonCancellable) { acquired?.close() }
        }
    }

    private suspend fun capture(workspaceId: String) =
        sessions.captureCollaborationOwner(workspaceId, workspaces, server, build, clock)

    private suspend fun continuation(
        workspaceId: String,
        owner: CollaborationOwner,
        received: AppResult<*>,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        return requireCollaborationOwner(capture(workspaceId), owner, received)
    }

    private companion object {
        val canonicalId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
